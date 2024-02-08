package it.sogei.pattern.rest.resources;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/test")
public class TestResource {

    private final static Logger logger = LoggerFactory.getLogger(TestResource.class.getSimpleName());

    @Inject
    @ConfigProperty(name="timer.table.name", defaultValue="LIBERTY_TABLE_NAME")
    private String timername;

    @GET
    @Path("/configproptest")
    public Response test1() {
        try {
            logger.info("Inoking test method...");
            logger.info("Variable read: timername="+timername);
            logger.info("Test successful...");
            return Response.ok("Test successful").build();
        }
        catch(Exception e) {
            logger.error("Error in test method: " + e.getLocalizedMessage(), e);
            return Response.serverError().entity(e.getLocalizedMessage()).build();
        }
    }

}
