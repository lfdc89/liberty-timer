package it.sogei.pattern.timer;

import it.sogei.pattern.task.SogeiTask;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.*;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.*;
import java.text.SimpleDateFormat;

@Startup
@Singleton
public class LibertyTimer {

    private final static Logger logger = LoggerFactory.getLogger(LibertyTimer.class.getSimpleName());

    private final SimpleDateFormat expfmt = new SimpleDateFormat("yyyy-MM-dd HH:mm::ss zzz");

    private String serverName;
    private String hostname;
    private String executor;
    private int preemptionInterval;
    private long firstLease;
    private boolean initialized = false;
    private boolean wasOwner = false;

    @Resource
    private SessionContext context;

    @Resource
    private TimerService timerService;

    @Resource(name="jdbc/ds.scheduler")
    DataSource ds;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_TABLE")
    private String tableName;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_DB_SCHEMA")
    private String dbSchema;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_NAME")
    private String timerName;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_BASE_INTERVAL", defaultValue = "60")
    private Integer baseInterval;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_BUSY_INTERVAL", defaultValue = "10")
    private Integer busyInterval;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_WAITING_THRESHOLD", defaultValue = "2")
    private Integer waitingThreshold;

    @Inject
    @ConfigProperty(name="TIMER_IDTIMER_MAX_OWNERSHIP", defaultValue = "1800")
    private Integer maxOwnership;

    @EJB
    SogeiTask sogeiTask;

    @PostConstruct
    public void initAndStart()
    {
        logger.info("initAndStart(): start");
        try
        {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName serverInfo = new ObjectName("WebSphere:feature=kernel,name=ServerInfo");

            if (mbs.isRegistered(serverInfo))
            {
                serverName = (String) mbs.getAttribute(serverInfo, "Name");
                logger.info("initAndStart(): serverName = " + serverName);
                if (serverName == null ) throw new RuntimeException("serverName is null");

                hostname = (String) mbs.getAttribute(serverInfo, "DefaultHostname");
                logger.info("initAndStart(): hostname = " + hostname);
                if (hostname == null ) throw new RuntimeException("hostname is null");

                executor=hostname+"-"+serverName;
                logger.info("initAndStart(): executor = " + executor);

                if (timerName == null || "".equals(timerName)) {
                    logger.info("initAndStart(): timerName = " + timerName);
                    throw new RuntimeException("timername is null. Configurare le variabili nel server.env");
                }
                if (tableName == null || "".equals(tableName)) {
                    logger.info("initAndStart(): tableName = " + tableName);
                    throw new RuntimeException("tableName is null. Configurare le variabili nel server.env");
                }
                if (dbSchema == null || "".equals(dbSchema)) {
                    logger.info("initAndStart(): dbSchema = " + dbSchema);
                    throw new RuntimeException("dbSchema is null. Configurare le variabili nel server.env");
                }
            }

            preemptionInterval = baseInterval * waitingThreshold;

            logger.info("initAndStart(): timername = " + timerName);
            logger.info("initAndStart(): baseInterval = " + baseInterval + " seconds");
            logger.info("initAndStart(): busyInterval = " + busyInterval + " seconds");
            logger.info("initAndStart(): waitingThreshold = " + waitingThreshold + " times");
            logger.info("initAndStart(): maxOwnership = " + maxOwnership + " seconds");
            logger.info("initAndStart(): preemptionInterval = " + preemptionInterval + " seconds");
        }

        catch (Exception err)
        {
            logger.error("initAndStart(): ERROR: " + err.getMessage());
            logger.info("initAndStart(): end");
            return;
        }

        long interval = checkOwnerOrIntervalTimer();

        if (interval == 0)
        {
            logger.info("initAndStart(): We are the lease owner or we have just acquired the lease (baseInterval)");
            setNextTimer(baseInterval);
            initialized = true;
        }
        else
        if (interval > 0)
        {
            logger.info("initAndStart(): We are NOT the lease owner (returned interval)");
            setNextTimer(interval);
            initialized = true;
        }
        else
        {
            logger.info("initAndStart(): We got a fatal error (preemptionInterval)");
            setNextTimer(preemptionInterval);
        }

        logger.info("initAndStart(): end");
    }

    // 0 = Lease Owner, > 0: Interval Timer, -1 = ERROR
    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    private long checkOwnerOrIntervalTimer()
    {
        logger.info("checkOwnerOrIntervalTimer(): start");

        long ret = -1;

        logger.debug("checkOwnerOrIntervalTimer(): timername = " + timerName);
        logger.debug("checkOwnerOrIntervalTimer(): executor = " + executor);

        Connection conn = null;
        Statement stmt = null;

        try
        {
            conn = ds.getConnection();
            logger.debug("checkOwnerOrIntervalTimer(): Acquired a database connection");

            boolean isLocked = false;
            stmt = conn.createStatement();

            for (int i = 0; i < baseInterval; ++i)
            {
                try
                {
                    stmt.execute(String.format("LOCK TABLE %s.%s  IN EXCLUSIVE MODE NOWAIT;", dbSchema,tableName));
                    isLocked = true;
                    break;
                }
                catch (SQLException err)
                {
                    logger.debug("checkOwnerOrIntervalTimer(): ERROR: " + err.getMessage());
                    Thread.sleep(1000L);
                }
            }

            stmt.close();
            stmt = null;

            if (!isLocked)
            {
                logger.error(String.format("checkOwnerOrIntervalTimer(): ERROR: Failed to lock table %s in %d seconds",tableName, baseInterval));
                logger.info(String.format("checkOwnerOrIntervalTimer(): end (ret = %d)", wasOwner ? 0 : -1));
                return wasOwner ? 0 : -1;
            }

            logger.debug(String.format("checkBeforeStart(): Table %s has been locked successfully",tableName));

            if (context.getRollbackOnly()) throw new RuntimeException("There is an active requested rollback (getRollbackOnly() == true");

            String ejbTimerQuery = String.format("SELECT SERVERNAME, LEASETIMESTAMP FROM %s.%s WHERE TIMERNAME = ?",dbSchema,tableName);
            PreparedStatement pstmt = conn.prepareStatement(ejbTimerQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
            stmt = pstmt;

            pstmt.setString(1, timerName);
            ResultSet rs = pstmt.executeQuery();

            if (context.getRollbackOnly()) throw new RuntimeException("There is an active requested rollback (getRollbackOnly() == true");

            if (rs.next())
            {
                long current = System.currentTimeMillis() / 1000L;
                logger.debug("checkOwnerOrIntervalTimer(): current timestamp in second = " + current);

                String leaseOwner = rs.getString("SERVERNAME");
                logger.debug("checkOwnerOrIntervalTimer(): lease owner (from database) = " + leaseOwner);

                long leaseTimestamp = rs.getLong("LEASETIMESTAMP");
                logger.debug("checkOwnerOrIntervalTimer(): lease timestamp (from database) = " + leaseTimestamp);

                boolean isOwner = executor.equals(leaseOwner);
                logger.info("checkOwnerOrIntervalTimer(): isOwner = " + isOwner);

                if (isOwner)
                {
                    logger.info(String.format("checkOwnerOrIntervalTimer(): We are the owner (%s)", leaseOwner));

                    if (firstLease == 0) firstLease = current;

                    if (current - firstLease < maxOwnership)
                    {
                        rs.updateLong("LEASETIMESTAMP", current);
                        rs.updateRow();
                        logger.info("checkOwnerOrIntervalTimer(): Updated the lease timestamp to " + current);
                        if (context.getRollbackOnly()) throw new RuntimeException("There is an active requested rollback (getRollbackOnly() == true");
                    }
                    else
                    {
                        logger.info("checkOwnerOrIntervalTimer(): Reached the max ownership timeout, lease timestamp will not be updated");
                    }

                    wasOwner = true;
                    ret = 0;
                }
                else
                {
                    logger.info(String.format("checkOwnerOrIntervalTimer(): We are NOT the owner (the owner is %s)", leaseOwner));

                    boolean isDead = (leaseTimestamp + preemptionInterval) < current;
                    logger.info("checkOwnerOrIntervalTimer(): leaseTimestamp = "+leaseTimestamp +" - preemptionInterval = "+preemptionInterval+" current = "+current+" isDead = " + isDead);

                    if (isDead)
                    {
                        rs.updateString("SERVERNAME", executor);
                        rs.updateLong("LEASETIMESTAMP", current);
                        rs.updateRow();
                        firstLease = current;
                        logger.info("checkOwnerOrIntervalTimer(): Forced the lease because the old owner is dead or is sleeping too much");
                        if (context.getRollbackOnly()) throw new RuntimeException("There is an active requested rollback (getRollbackOnly() == true");

                        wasOwner = true;
                        ret = baseInterval;
                        logger.info("checkOwnerOrIntervalTimer(): Skip next cycle - set interval to "+baseInterval);
                    }
                    else
                    {
                        wasOwner = false;
                        ret = leaseTimestamp + preemptionInterval - current + 1;
                        firstLease = 0;
                    }
                }

                stmt.close();
                stmt = null;
            }
            else
            {
                logger.debug("checkOwnerOrIntervalTimer(): No rows found, so we force to become the first lease owner");

                long current = System.currentTimeMillis() / 1000L;
                logger.debug("checkOwnerOrIntervalTimer(): current timestamp in second = " + current);

                stmt = conn.createStatement();
                int row = stmt.executeUpdate(String.format("INSERT INTO %s.%s (TIMERNAME, SERVERNAME, LEASETIMESTAMP) VALUES ('%s', '%s', %d)",dbSchema,tableName, timerName, executor, current));
                logger.debug("checkOwnerOrIntervalTimer(): inserted row = " + row);

                stmt.close();
                stmt = null;

                if (context.getRollbackOnly()) throw new RuntimeException("There is an active requested rollback (getRollbackOnly() == true");

                if (row == 1)
                {
                    wasOwner = true;
                    ret = 0;
                }
                else
                {
                    wasOwner = false;
                    ret = -1;
                }
            }
        }
        catch (Exception err)
        {
            logger.error("checkOwnerOrIntervalTimer(): ERROR: " + err.getMessage());
            if (!context.getRollbackOnly() && conn != null) context.setRollbackOnly();
            ret = -1;
        }
        finally
        {
            if (stmt != null) try { stmt.close(); } catch (Exception err) {}
            if (conn != null) try { conn.close(); } catch (Exception err) {}
        }
        logger.info(String.format("checkOwnerOrIntervalTimer(): end (ret = %d)", ret));
        return ret;
    }

    private void setNextTimer(long interval)
    {
        logger.info("setNextTimer(long interval): start");
        logger.debug("setNextTimer(long interval): interval = " + interval);

        TimerConfig tc = new TimerConfig();
        tc.setPersistent(false);
        logger.info("setNextTimer(long interval): TimerConfig not persistent instance created");

        Timer timer = timerService.createSingleActionTimer(interval * 1000L, tc);
        if (timer != null)
        {
            logger.debug("setNextTimer(long interval): Set next single action timer successfully");
            String nextTimeout = expfmt.format(timer.getNextTimeout());
            logger.info("setNextTimer(long interval): nextTimeout = " + nextTimeout);
        }
        else
        {
            logger.error("setNextTimer(long interval): ERROR: Failed to set next single action timer");
        }
        logger.info("setNextTimer(long interval): end");
    }

    @Timeout
    public void execute(Timer timer)
    {
        logger.info("execute(Timer timer): start");

        //verifica se siamo o no l'owner del lease
        logger.debug("execute(Timer timer): check who is the owner ...");
        long interval = checkOwnerOrIntervalTimer();
        logger.debug("execute(Timer timer): interval = " + interval);

        if (interval == 0) //owner
        {
            logger.info("execute(Timer timer): run the action because we are the owner");

            //possibilità di inserire eventuali inizializzazioni del db di business
            if (!initialized)
            {
                //log.info("LibertyTimer.execute(Timer timer): run dbInizialization() because it did not before and now we are the owner");
                //dbInizialization();
                initialized = true;
            }
            /*
             * Check se è possibile eseguire l'operazione a fronte di un controllo applicativo, se si eseguo azione e setto baseInterval
             * se no setto busyInterval e non faccio altro.
             * se non c'è questa esigenza commentare l'if oppure ritornare sempre true
             */
            try
            {
                //Inserire esecuzione Task
                //TODO: da modificare con i metodi della propria classe di business
                logger.debug("execute(Timer timer): Eseguo task");
                setNextTimer(baseInterval);
                sogeiTask.taskMethod();
            }
            catch (Exception e)
            {
                logger.error(String.format("execute(Timer timer): ERROR: %s [Class = %s]", e.getMessage(), e.getClass().getName()));
                setNextTimer(baseInterval);
            }
        }
        else
        if (interval > 0)
        {
            logger.info("execute(Timer timer): skip the action because we are NOT the owner or we have just acquired the lease");
            setNextTimer(interval);
            initialized = true;
        }
        else
        {
            logger.info(String.format("execute(Timer timer): caught a severe error, we will retry in %d seconds", preemptionInterval));
            setNextTimer(preemptionInterval);
        }
        logger.info("execute(Timer timer): end");
    }

}

