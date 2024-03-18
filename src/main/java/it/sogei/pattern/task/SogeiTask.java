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

    /*
     * esempio che randomicamente ritorna il booleano per il busy
     * da modificare
     */

    public boolean isBusy() throws TimerTaskException {
        return false;
    	/*
    	LocalTime orario=LocalTime.now();
    	int minuto=orario.getMinute();
    	logger.info("SogeiTask.isBusy(): minuto="+minuto);
    	if (minuto>10 && minuto <13) {
    		logger.info("SogeiTask.isBusy(): false");
			return false;
    	}
    	else {
    		logger.info("SogeiTask.isBusy(): true");
			return true;

    	}
    		/*
    	if (((new Date()).getTime()%2)==0) {
    		logger.info("SogeiTask.isBusy(): true");
    		return true;
    	}
    	else {
    		logger.info("SogeiTask.isBusy(): false");
    		return false;
    	}
    	*/
    }
}
