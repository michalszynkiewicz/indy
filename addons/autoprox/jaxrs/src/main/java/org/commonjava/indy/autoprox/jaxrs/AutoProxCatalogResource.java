/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.autoprox.jaxrs;

import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.created;
import static javax.ws.rs.core.Response.noContent;
import static javax.ws.rs.core.Response.ok;
import static javax.ws.rs.core.Response.status;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatCreatedResponseWithJsonEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatOkResponseWithJsonEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponse;

import java.io.IOException;
import java.net.URI;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.commonjava.indy.autoprox.conf.AutoProxConfig;
import org.commonjava.indy.autoprox.data.AutoProxRuleException;
import org.commonjava.indy.autoprox.rest.AutoProxAdminController;
import org.commonjava.indy.autoprox.rest.dto.CatalogDTO;
import org.commonjava.indy.autoprox.rest.dto.RuleDTO;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.SecurityManager;
import org.commonjava.indy.util.ApplicationContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

@Path( "/api/autoprox/catalog" )
public class AutoProxCatalogResource
    implements IndyResources
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ObjectMapper serializer;

    @Inject
    private AutoProxAdminController controller;

    @Inject
    private SecurityManager securityManager;

    @Inject
    private AutoProxConfig config;

    private Response checkEnabled()
    {
        if ( !config.isEnabled() )
        {
            return Response.status( Response.Status.BAD_REQUEST ).entity( "Autoprox is disabled." ).build();
        }

        return null;
    }

    @DELETE
    public Response reparseCatalog()
    {
        Response response = checkEnabled();
        if ( response != null )
        {
            return response;
        }

        try
        {
            controller.reparseCatalog();
            response = Response.ok()
                               .build();
        }
        catch ( final AutoProxRuleException e )
        {
            logger.error( String.format( "Failed to reparse catalog from disk. Reason: %s", e.getMessage() ), e );
            response = formatResponse( e );
        }

        return response;
    }

    @GET
    @Consumes( ApplicationContent.application_json )
    public Response getCatalog()
    {
        Response response = checkEnabled();
        if ( response != null )
        {
            return response;
        }

        final CatalogDTO dto = controller.getCatalog();
        if ( dto == null )
        {
            return Response.status( Status.NOT_FOUND )
                           .build();
        }

        return formatOkResponseWithJsonEntity( dto, serializer );
    }

    @GET
    @Path( "{name}" )
    public Response getRule( @PathParam( "name" ) final String name )
    {
        Response response = checkEnabled();
        if ( response != null )
        {
            return response;
        }

        final RuleDTO dto = controller.getRule( name );
        if ( dto == null )
        {
            return Response.status( Status.NOT_FOUND )
                           .build();
        }
        else
        {
            return formatOkResponseWithJsonEntity( dto, serializer );
        }
    }

    @DELETE
    @Path( "{name}" )
    public Response deleteRule( @PathParam( "name" ) final String name, @Context final HttpServletRequest request,
                                @Context final SecurityContext securityContext )
    {
        Response response = checkEnabled();
        if ( response != null )
        {
            return response;
        }

        try
        {
            String user = securityManager.getUser( securityContext, request );

            final RuleDTO dto = controller.deleteRule( name, user );
            if ( dto == null )
            {
                response = status( NOT_FOUND ).build();
            }
            else
            {
                response = noContent().build();
            }
        }
        catch ( final AutoProxRuleException e )
        {
            logger.error( String.format( "Failed to delete rule: %s. Reason: %s", name, e.getMessage() ), e );
            response = formatResponse( e );
        }

        return response;
    }

    @POST
    @Consumes( ApplicationContent.application_json )
    public Response createRule( @Context final HttpServletRequest request, @Context final UriInfo uriInfo,
                                @Context SecurityContext securityContext )
    {
        Response response = checkEnabled();
        if ( response != null )
        {
            return response;
        }

        RuleDTO dto = null;
        try
        {
            dto = serializer.readValue( request.getInputStream(), RuleDTO.class );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to read " + RuleDTO.class.getSimpleName() + " from request body.";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        if ( response != null )
        {
            return response;
        }

        try
        {
            String user = securityManager.getUser( securityContext, request );

            dto = controller.storeRule( dto, user );

            final URI uri = uriInfo.getBaseUriBuilder().path( getClass() ).path( dto.getName() ).build();

            response = formatCreatedResponseWithJsonEntity( uri, dto, serializer );
        }
        catch ( final AutoProxRuleException e )
        {
            final String message = "Failed to store rule: " + dto.getName() + ".";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        return response;
    }

    @PUT
    @Path( "{name}" )
    @Consumes( ApplicationContent.application_json )
    public Response updateRule( @PathParam( "name" ) final String name, @Context final HttpServletRequest request,
                                @Context final UriInfo uriInfo, @Context final SecurityContext securityContext )
    {
        Response response = checkEnabled();
        if ( response != null )
        {
            return response;
        }

        RuleDTO dto = controller.getRule( name );

        boolean updating = true;
        if ( dto == null )
        {
            updating = false;
        }

        try
        {
            dto = serializer.readValue( request.getInputStream(), RuleDTO.class );
            dto.setName( name );
        }
        catch ( final IOException e )
        {
            final String message = "Failed to read " + RuleDTO.class.getSimpleName() + " from request body.";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        if ( response != null )
        {
            return response;
        }

        try
        {
            String user = securityManager.getUser( securityContext, request );

            dto = controller.storeRule( dto, user );

            if ( updating )
            {
                response = ok().build();
            }
            else
            {
                final URI uri = uriInfo.getBaseUriBuilder().path( getClass() ).path( dto.getName() ).build();

                response = created( uri ).build();
            }
        }
        catch ( final AutoProxRuleException e )
        {
            final String message = "Failed to store rule: " + dto.getName() + ".";

            logger.error( message, e );
            response = formatResponse( e, message );
        }

        return response;
    }
}
