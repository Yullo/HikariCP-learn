/*
 * Copyright (C) 2013,2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.pool;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.metrics.PoolStats;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleHealthChecker;
import com.zaxxer.hikari.metrics.dropwizard.CodahaleMetricsTrackerFactory;
import com.zaxxer.hikari.util.ClockSource;
import com.zaxxer.hikari.util.ConcurrentBag;
import com.zaxxer.hikari.util.ConcurrentBag.IBagStateListener;
import com.zaxxer.hikari.util.SuspendResumeLock;
import com.zaxxer.hikari.util.UtilityElf.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zaxxer.hikari.pool.PoolEntry.LASTACCESS_COMPARABLE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.UtilityElf.createThreadPoolExecutor;
import static com.zaxxer.hikari.util.UtilityElf.quietlySleep;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This is the primary connection pool class that provides the basic
 * pooling behavior for HikariCP.
 *
 * @author Brett Wooldridge
 */
public class HikariPool extends PoolBase implements HikariPoolMXBean, IBagStateListener
{
   private final Logger LOGGER = LoggerFactory.getLogger(HikariPool.class);

   private static final ClockSource clockSource = ClockSource.INSTANCE;

   private static final int POOL_NORMAL = 0;
   private static final int POOL_SUSPENDED = 1;
   private static final int POOL_SHUTDOWN = 2;

   private volatile int poolState;

   private final long ALIVE_BYPASS_WINDOW_MS = Long.getLong("com.zaxxer.hikari.aliveBypassWindowMs", MILLISECONDS.toMillis(500));
   private final long HOUSEKEEPING_PERIOD_MS = Long.getLong("com.zaxxer.hikari.housekeeping.periodMs", SECONDS.toMillis(30));

   private final PoolEntryCreator POOL_ENTRY_CREATOR = new PoolEntryCreator();
   private final AtomicInteger totalConnections;
   private final ThreadPoolExecutor addConnectionExecutor;
   private final ThreadPoolExecutor closeConnectionExecutor;
   private final ScheduledThreadPoolExecutor houseKeepingExecutorService;

   private final ConcurrentBag<PoolEntry> connectionBag;

   private final ProxyLeakTask leakTask;
   private final SuspendResumeLock suspendResumeLock;

   private MetricsTrackerDelegate metricsTracker;

   /**
    * Construct a HikariPool with the specified configuration.
    *
    * @param config a HikariConfig instance
    */
   public HikariPool(final HikariConfig config)
   {
      // 首先执行父类方法，初始化底层
      super(config);

      // 连接存放的地方
      this.connectionBag = new ConcurrentBag<>(this);
      this.totalConnections = new AtomicInteger();
      // 信号量 ，控制并发量，支持挂起
      this.suspendResumeLock = config.isAllowPoolSuspension() ? new SuspendResumeLock() : SuspendResumeLock.FAUX_LOCK;

      // 快速失败的检查， 进行底层的校验，并创建一个连接执行初始化sql,看能否成功
      checkFailFast();

      if (config.getMetricsTrackerFactory() != null) {
         setMetricsTrackerFactory(config.getMetricsTrackerFactory());
      }
      else {
         setMetricRegistry(config.getMetricRegistry());
      }

      setHealthCheckRegistry(config.getHealthCheckRegistry());

      registerMBeans(this);

      ThreadFactory threadFactory = config.getThreadFactory();
      // 创建线程池，补充连接
      this.addConnectionExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection adder", threadFactory, new ThreadPoolExecutor.DiscardPolicy());
      // 创建线程池，关闭连接
      this.closeConnectionExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection closer", threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());

      if (config.getScheduledExecutorService() == null) {
         threadFactory = threadFactory != null ? threadFactory : new DefaultThreadFactory(poolName + " housekeeper", true);
         // 创建延迟任务线程池，核心线程数为1
         // 这个线程池用来检测连接过期，连接泄漏等延迟任务，是线程池闲时运行的主要线程池
         this.houseKeepingExecutorService = new ScheduledThreadPoolExecutor(1, threadFactory, new ThreadPoolExecutor.DiscardPolicy());
         this.houseKeepingExecutorService.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
         this.houseKeepingExecutorService.setRemoveOnCancelPolicy(true);
      }
      else {
         this.houseKeepingExecutorService = config.getScheduledExecutorService();
      }

      // 泄漏检测， 这个检测只会打一条log,没有具体策略
      this.leakTask = new ProxyLeakTask(config.getLeakDetectionThreshold(), houseKeepingExecutorService);

      // 守护线程
      this.houseKeepingExecutorService.scheduleWithFixedDelay(new HouseKeeper(), 100L, HOUSEKEEPING_PERIOD_MS, MILLISECONDS);
   }

   /**
    * Get a connection from the pool, or timeout after connectionTimeout milliseconds.
    *
    * @return a java.sql.Connection instance
    * @throws SQLException thrown if a timeout occurs trying to obtain a connection
    */
   public final Connection getConnection() throws SQLException
   {
      return getConnection(connectionTimeout);
   }

   /**
    * Get a connection from the pool, or timeout after the specified number of milliseconds.
    *
    * @param hardTimeout the maximum time to wait for a connection from the pool
    * @return a java.sql.Connection instance
    * @throws SQLException thrown if a timeout occurs trying to obtain a connection
    */
   public final Connection getConnection(final long hardTimeout) throws SQLException
   {
      // 获取一个信号量许可
      suspendResumeLock.acquire();
      final long startTime = clockSource.currentTime();

      try {
         long timeout = hardTimeout;
         do {
            // 从池中拿取连接
            final PoolEntry poolEntry = connectionBag.borrow(timeout, MILLISECONDS);
            // 返回null说明超时了,池中没有可用连接，直接跳出循环，到最后一行，打印一条timeout异常
            if (poolEntry == null) {
               break; // We timed out... break and throw exception
            }

            final long now = clockSource.currentTime();
            // 获取到连接， 检查
            // 是否已标记为废弃
            if (poolEntry.isMarkedEvicted() ||
               // 如何距离上次使用超过了500ms， 检查底层连接是否存活
               (clockSource.elapsedMillis(poolEntry.lastAccessed, now) > ALIVE_BYPASS_WINDOW_MS && !isConnectionAlive(poolEntry.connection))) {
               // 如果已废弃 或者 不存活， 关闭拿出来的这个连接， 循环获取下一个
               closeConnection(poolEntry, "(connection is evicted or dead)"); // Throw away the dead connection (passed max age or failed alive test)
               timeout = hardTimeout - clockSource.elapsedMillis(startTime);
            }
            else {
               metricsTracker.recordBorrowStats(poolEntry, startTime);
               // 如果连接好使，包装成ProxyConnection返回
               return poolEntry.createProxyConnection(leakTask.schedule(poolEntry), now);
            }
         } while (timeout > 0L);
      }
      catch (InterruptedException e) {
         throw new SQLException(poolName + " - Interrupted during connection acquisition", e);
      }
      finally {
         suspendResumeLock.release();
      }
      // 如果在超时时间内拿出的连接没一个好使的，打印一条timeout异常
      // 只有两种情况会走到这里：
      //  1. 池中无连接可用时，获取不到连接导致超时， （链接耗尽 或者 链接泄漏）
      //  2. 连续从池中拿出的链接不可用（被回收 或 失效），导致超过时间限制
      throw createTimeoutException(startTime);
   }

   /**
    * Shutdown the pool, closing all idle connections and aborting or closing
    * active connections.
    *
    * @throws InterruptedException thrown if the thread is interrupted during shutdown
    */
   public final synchronized void shutdown() throws InterruptedException
   {
      try {
         poolState = POOL_SHUTDOWN;

         if (addConnectionExecutor == null) {
            return;
         }

         LOGGER.info("{} - Close initiated...", poolName);
         logPoolState("Before closing ");

         softEvictConnections();

         addConnectionExecutor.shutdown();
         addConnectionExecutor.awaitTermination(5L, SECONDS);

         if (config.getScheduledExecutorService() == null) {
            houseKeepingExecutorService.shutdown();
            houseKeepingExecutorService.awaitTermination(5L, SECONDS);
         }

         connectionBag.close();

         final ExecutorService assassinExecutor = createThreadPoolExecutor(config.getMaximumPoolSize(), poolName + " connection assassinator",
                                                                           config.getThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy());
         try {
            final long start = clockSource.currentTime();
            do {
               abortActiveConnections(assassinExecutor);
               softEvictConnections();
            } while (getTotalConnections() > 0 && clockSource.elapsedMillis(start) < SECONDS.toMillis(5));
         }
         finally {
            assassinExecutor.shutdown();
            assassinExecutor.awaitTermination(5L, SECONDS);
         }

         shutdownNetworkTimeoutExecutor();
         closeConnectionExecutor.shutdown();
         closeConnectionExecutor.awaitTermination(5L, SECONDS);
      }
      finally {
         logPoolState("After closing ");
         unregisterMBeans();
         metricsTracker.close();
         LOGGER.info("{} - Closed.", poolName);
      }
   }

   /**
    * Evict a connection from the pool.
    *
    * @param connection the connection to evict
    */
   public final void evictConnection(Connection connection)
   {
      ProxyConnection proxyConnection = (ProxyConnection) connection;
      proxyConnection.cancelLeakTask();

      try {
         softEvictConnection(proxyConnection.getPoolEntry(), "(connection evicted by user)", !connection.isClosed() /* owner */);
      }
      catch (SQLException e) {
         // unreachable in HikariCP, but we're still forced to catch it
      }
   }

   public void setMetricRegistry(Object metricRegistry)
   {
      if (metricRegistry != null) {
         setMetricsTrackerFactory(new CodahaleMetricsTrackerFactory((MetricRegistry) metricRegistry));
      }
      else {
         setMetricsTrackerFactory(null);
      }
   }

   public void setMetricsTrackerFactory(MetricsTrackerFactory metricsTrackerFactory)
   {
      if (metricsTrackerFactory != null) {
         this.metricsTracker = new MetricsTrackerDelegate(metricsTrackerFactory.create(config.getPoolName(), getPoolStats()));
      }
      else {
         this.metricsTracker = new NopMetricsTrackerDelegate();
      }
   }

   public void setHealthCheckRegistry(Object healthCheckRegistry)
   {
      if (healthCheckRegistry != null) {
         CodahaleHealthChecker.registerHealthChecks(this, config, (HealthCheckRegistry) healthCheckRegistry);
      }
   }

   // ***********************************************************************
   //                        IBagStateListener callback
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   // 往池中添加连接
   public Future<Boolean> addBagItem()
   {
      // 使用添加线程池执行
      return addConnectionExecutor.submit(POOL_ENTRY_CREATOR);
   }

   // ***********************************************************************
   //                        HikariPoolMBean methods
   // ***********************************************************************

   /** {@inheritDoc} */
   @Override
   public final int getActiveConnections()
   {
      return connectionBag.getCount(STATE_IN_USE);
   }

   /** {@inheritDoc} */
   @Override
   public final int getIdleConnections()
   {
      return connectionBag.getCount(STATE_NOT_IN_USE);
   }

   /** {@inheritDoc} */
   @Override
   public final int getTotalConnections()
   {
      return connectionBag.size() - connectionBag.getCount(STATE_REMOVED);
   }

   /** {@inheritDoc} */
   @Override
   public final int getThreadsAwaitingConnection()
   {
      return connectionBag.getPendingQueue();
   }

   /** {@inheritDoc} */
   @Override
   public void softEvictConnections()
   {
      for (PoolEntry poolEntry : connectionBag.values()) {
         softEvictConnection(poolEntry, "(connection evicted)", false /* not owner */);
      }
   }

   /** {@inheritDoc} */
   @Override
   public final synchronized void suspendPool()
   {
      if (suspendResumeLock == SuspendResumeLock.FAUX_LOCK) {
         throw new IllegalStateException(poolName + " - is not suspendable");
      }
      else if (poolState != POOL_SUSPENDED) {
         suspendResumeLock.suspend();
         poolState = POOL_SUSPENDED;
      }
   }

   /** {@inheritDoc} */
   @Override
   public final synchronized void resumePool()
   {
      if (poolState == POOL_SUSPENDED) {
         poolState = POOL_NORMAL;
         fillPool();
         suspendResumeLock.resume();
      }
   }

   // ***********************************************************************
   //                           Package methods
   // ***********************************************************************

   /**
    * Log the current pool state at debug level.
    *
    * @param prefix an optional prefix to prepend the log message
    */
   final void logPoolState(String... prefix)
   {
      if (LOGGER.isDebugEnabled()) {
         LOGGER.debug("{} - {}stats (total={}, active={}, idle={}, waiting={})",
                      poolName, (prefix.length > 0 ? prefix[0] : ""),
                      getTotalConnections(), getActiveConnections(), getIdleConnections(), getThreadsAwaitingConnection());
      }
   }

   /**
    * Recycle PoolEntry (add back to the pool)
    *
    * @param poolEntry the PoolEntry to recycle
    */
   @Override
   final void recycle(final PoolEntry poolEntry)
   {
      metricsTracker.recordConnectionUsage(poolEntry);

      connectionBag.requite(poolEntry);
   }

   /**
    * Permanently close the real (underlying) connection (eat any exception).
    *
    * @param poolEntry poolEntry having the connection to close
    * @param closureReason reason to close
    */
   final void closeConnection(final PoolEntry poolEntry, final String closureReason)
   {
      LOGGER.debug("close ... status:{}", poolEntry.getState());
      // 从池中移除
      if (connectionBag.remove(poolEntry)) {
         final int tc = totalConnections.decrementAndGet();
         if (tc < 0) {
            LOGGER.warn("{} - Unexpected value of totalConnections={}", poolName, tc, new Exception());
         }
         final Connection connection = poolEntry.close();
         closeConnectionExecutor.execute(new Runnable() {
            @Override
            public void run() {
               // 使用关闭线程池，调用引擎关闭连接
               quietlyCloseConnection(connection, closureReason);
            }
         });
      }
   }

   // ***********************************************************************
   //                           Private methods
   // ***********************************************************************

   /**
    * Creating new poolEntry.
    */
   private PoolEntry createPoolEntry()
   {
      try {
         // 将底层连接包装成 PoolEntry
         final PoolEntry poolEntry = newPoolEntry();

         final long maxLifetime = config.getMaxLifetime();
         if (maxLifetime > 0) {
            // variance up to 2.5% of the maxlifetime
            final long variance = maxLifetime > 10_000 ? ThreadLocalRandom.current().nextLong( maxLifetime / 40 ) : 0;
            // 为了避免同时失效，这里生成一个随机数，设置值减去随机数即为实际的生存时间
            final long lifetime = maxLifetime - variance;
            poolEntry.setFutureEol(houseKeepingExecutorService.schedule(new Runnable() {
               @Override
               public void run() {
                  // 到期就关闭连接， 这里的关闭是软关闭
                  softEvictConnection(poolEntry, "(connection has passed maxLifetime)", false /* not owner */);
               }
            }, lifetime, MILLISECONDS));
         }

         LOGGER.debug("{} - Added connection {}", poolName, poolEntry.connection);
         return poolEntry;
      }
      catch (Exception e) {
         if (poolState == POOL_NORMAL) {
            LOGGER.debug("{} - Cannot acquire connection from data source", poolName, e);
         }
         return null;
      }
   }

   /**
    * Fill pool up from current idle connections (as they are perceived at the point of execution) to minimumIdle connections.
    */
   private void fillPool()
   {
      final int connectionsToAdd = Math.min(config.getMaximumPoolSize() - totalConnections.get(), config.getMinimumIdle() - getIdleConnections())
                                   - addConnectionExecutor.getQueue().size();
      for (int i = 0; i < connectionsToAdd; i++) {
         addBagItem();
      }

      if (connectionsToAdd > 0 && LOGGER.isDebugEnabled()) {
         addConnectionExecutor.execute(new Runnable() {
            @Override
            public void run() {
               logPoolState("After adding ");
            }
         });
      }
   }

   /**
    * Attempt to abort or close active connections.
    */
   private void abortActiveConnections(final ExecutorService assassinExecutor)
   {
      for (PoolEntry poolEntry : connectionBag.values(STATE_IN_USE)) {
         Connection connection = poolEntry.close();
         try {
            connection.abort(assassinExecutor);
         }
         catch (Throwable e) {
            quietlyCloseConnection(connection, "(connection aborted during shutdown)");
         }
         finally {
            if (connectionBag.remove(poolEntry)) {
               totalConnections.decrementAndGet();
            }
         }
      }
   }

   /**
    * If initializationFailFast is configured, check that we have DB connectivity.
    *
    * @throws PoolInitializationException if fails to create or validate connection
    */
   private void checkFailFast()
   {
      if (config.isInitializationFailFast()) {
         try (Connection connection = newConnection()) {
            if (!connection.getAutoCommit()) {
               connection.commit();
            }
         }
         catch (Throwable e) {
            throw new PoolInitializationException(e);
         }
      }
   }

   private void softEvictConnection(final PoolEntry poolEntry, final String reason, final boolean owner)
   {
      poolEntry.markEvicted();
      if (owner || connectionBag.reserve(poolEntry)) {
         closeConnection(poolEntry, reason);
      }
   }

   private PoolStats getPoolStats()
   {
      return new PoolStats(SECONDS.toMillis(1)) {
         @Override
         protected void update() {
            this.pendingThreads = HikariPool.this.getThreadsAwaitingConnection();
            this.idleConnections = HikariPool.this.getIdleConnections();
            this.totalConnections = HikariPool.this.getTotalConnections();
            this.activeConnections = HikariPool.this.getActiveConnections();
         }
      };
   }

   private SQLException createTimeoutException(long startTime)
   {
      logPoolState("Timeout failure ");
      metricsTracker.recordConnectionTimeout();

      String sqlState = null;
      final Throwable originalException = getLastConnectionFailure();
      if (originalException instanceof SQLException) {
         sqlState = ((SQLException) originalException).getSQLState();
      }
      final SQLException connectionException = new SQLTransientConnectionException(poolName + " - Connection is not available, request timed out after " + clockSource.elapsedMillis(startTime) + "ms.", sqlState, originalException);
      if (originalException instanceof SQLException) {
         connectionException.setNextException((SQLException) originalException);
      }

      return connectionException;
   }

   // ***********************************************************************
   //                      Non-anonymous Inner-classes
   // ***********************************************************************

   /**
    * Creating and adding poolEntries (connections) to the pool.
    */
   private class PoolEntryCreator implements Callable<Boolean>
   {
      @Override
      public Boolean call() throws Exception
      {
         long sleepBackoff = 250L;
         while (poolState == POOL_NORMAL && totalConnections.get() < config.getMaximumPoolSize()) {
            // 创建一个entry
            final PoolEntry poolEntry = createPoolEntry();
            if (poolEntry != null) {
               // 计数
               totalConnections.incrementAndGet();
               // 添加到池中
               connectionBag.add(poolEntry);
               return Boolean.TRUE;
            }

            // failed to get connection from db, sleep and retry
            quietlySleep(sleepBackoff);
            sleepBackoff = Math.min(SECONDS.toMillis(10), Math.min(connectionTimeout, (long) (sleepBackoff * 1.5)));
         }
         // Pool is suspended or shutdown or at max size
         return Boolean.FALSE;
      }
   }

   /**
    * The house keeping task to retire and maintain minimum idle connections.
    */
   // 定时检查连接，清除闲置连接
   private class HouseKeeper implements Runnable
   {
      private volatile long previous = clockSource.plusMillis(clockSource.currentTime(), -HOUSEKEEPING_PERIOD_MS);

      @Override
      public void run()
      {
         //LOGGER.debug("HouseKeeper task runing .... ");
         try {
            // refresh timeouts in case they changed via MBean
            connectionTimeout = config.getConnectionTimeout();
            validationTimeout = config.getValidationTimeout();
            leakTask.updateLeakDetectionThreshold(config.getLeakDetectionThreshold());

            final long idleTimeout = config.getIdleTimeout();
            final long now = clockSource.currentTime();

            // Detect retrograde time, allowing +128ms as per NTP spec.
            // 处理时钟回拨
            if (clockSource.plusMillis(now, 128) < clockSource.plusMillis(previous, HOUSEKEEPING_PERIOD_MS)) {
               LOGGER.warn("{} - Retrograde clock change detected (housekeeper delta={}), soft-evicting connections from pool.",
                           poolName, clockSource.elapsedDisplayString(previous, now));
               previous = now;
               // 先关闭再添加，相当于重置线程池
               softEvictConnections();
               fillPool();
               return;
            }
            else if (now > clockSource.plusMillis(previous, (3 * HOUSEKEEPING_PERIOD_MS) / 2)) {
               // No point evicting for forward clock motion, this merely accelerates connection retirement anyway
               LOGGER.warn("{} - Thread starvation or clock leap detected (housekeeper delta={}).", poolName, clockSource.elapsedDisplayString(previous, now));
            }

            previous = now;

            String afterPrefix = "Pool ";
            if (idleTimeout > 0L) {
               // 获取所有闲置连接
               final List<PoolEntry> idleList = connectionBag.values(STATE_NOT_IN_USE);
               int removable = idleList.size() - config.getMinimumIdle();
               if (removable > 0) {
                  logPoolState("Before cleanup ");
                  afterPrefix = "After cleanup  ";

                  // Sort pool entries on lastAccessed
                  idleList.sort(LASTACCESS_COMPARABLE);
                  for (PoolEntry poolEntry : idleList) {
                     if (clockSource.elapsedMillis(poolEntry.lastAccessed, now) > idleTimeout && connectionBag.reserve(poolEntry)) {
                        closeConnection(poolEntry, "(connection has passed idleTimeout)");
                        if (--removable == 0) {
                           break; // keep min idle cons
                        }
                     }
                  }
               }
            }

            logPoolState(afterPrefix);

            // 补充连接
            fillPool(); // Try to maintain minimum connections
         }
         catch (Exception e) {
            LOGGER.error("Unexpected exception in housekeeping task", e);
         }
      }
   }

   public static class PoolInitializationException extends RuntimeException
   {
      private static final long serialVersionUID = 929872118275916520L;

      /**
       * Construct an exception, possibly wrapping the provided Throwable as the cause.
       * @param t the Throwable to wrap
       */
      public PoolInitializationException(Throwable t)
      {
         super("Failed to initialize pool: " + t.getMessage(), t);
      }
   }
}
