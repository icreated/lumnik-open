package io.lumnik.hub.hook;

import io.lumnik.common.error.BusinessException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.HashMap;
import java.util.Map;

@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {
    @Override
    public Response toResponse(BusinessException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", ex.code());
        body.put("message", ex.getMessage());
        if (ex.details() != null && !ex.details().isEmpty()) body.put("details", ex.details());
        return Response.status(409).entity(body).build();
    }
}
