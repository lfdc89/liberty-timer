package it.sogei.pattern.task;

import it.sogei.pattern.exceptions.TimerTaskException;
import jakarta.ejb.LocalBean;
import jakarta.ejb.Stateless;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session Bean implementation class SogeiTask
 */
@Stateless
@LocalBean
public class SogeiTask {

    private static final Logger logger = LoggerFactory.getLogger(SogeiTask.class.getSimpleName());

    public void taskMethod() throws TimerTaskException {
        logger.info("taskMethod(): start");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            logger.error(e.getMessage());
            throw new TimerTaskException("Errore nell'esecuzione del task");
        }
        logger.info("taskMethod(): end");

    }
}
