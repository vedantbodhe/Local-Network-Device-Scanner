package com.example.scanner.api;

import com.example.scanner.core.NetworkScanner;
import com.example.scanner.core.NetworkScanner.JobProgress;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/scan")
@Produces(MediaType.APPLICATION_JSON)
public class ScanResource {

    @Inject
    NetworkScanner scanner;

    @POST
    @Path("/start")
    public Response start(@QueryParam("cidr") String cidr,
                          @QueryParam("timeoutMs") @DefaultValue("300") int timeoutMs) {
        if (cidr == null || cidr.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"cidr required\"}").build();
        }
        String jobId = scanner.start(cidr, timeoutMs);
        return Response.ok("{\"jobId\":\"" + jobId + "\"}").build();
    }

    @GET
    @Path("/progress/{id}")
    public Response progress(@PathParam("id") String id) {
        try {
            JobProgress p = scanner.progress(id);
            return Response.ok(p).build();
        } catch (NetworkScanner.JobNotFound e) {
            return Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"job not found\"}").build();
        }
    }
}