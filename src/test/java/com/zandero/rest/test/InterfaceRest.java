package com.zandero.rest.test;

import javax.ws.rs.*;

/**
 *
 */
@Path("interface")
@Produces("application/json")
public interface InterfaceRest {

	@GET
	@Consumes("application/json")
	@Path("echo")
	String echo(@QueryParam("name") String name);
}
