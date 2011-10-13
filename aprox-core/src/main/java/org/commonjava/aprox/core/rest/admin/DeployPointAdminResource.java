/*******************************************************************************
 * Copyright (C) 2011  John Casey
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public
 * License along with this program.  If not, see 
 * <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.commonjava.aprox.core.rest.admin;

import javax.annotation.PostConstruct;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.commonjava.aprox.core.data.ProxyDataException;
import org.commonjava.aprox.core.data.ProxyDataManager;
import org.commonjava.aprox.core.model.DeployPoint;
import org.commonjava.aprox.core.model.StoreType;
import org.commonjava.aprox.core.model.io.StoreKeySerializer;
import org.commonjava.auth.couch.model.Permission;
import org.commonjava.util.logging.Logger;
import org.commonjava.web.common.model.Listing;
import org.commonjava.web.common.ser.JsonSerializer;

import com.google.gson.reflect.TypeToken;

@Path( "/admin/deploy" )
@RequestScoped
@RequiresAuthentication
public class DeployPointAdminResource
{

    private final Logger logger = new Logger( getClass() );

    @Inject
    private ProxyDataManager proxyManager;

    @Inject
    private JsonSerializer restSerializer;

    @Context
    private UriInfo uriInfo;

    @Context
    private HttpServletRequest request;

    @PostConstruct
    protected void registerSerializationAdapters()
    {
        restSerializer.registerSerializationAdapters( new StoreKeySerializer() );
    }

    @POST
    @Consumes( { MediaType.APPLICATION_JSON } )
    public Response create()
    {
        SecurityUtils.getSubject().isPermitted( Permission.name( StoreType.repository.name(),
                                                                 Permission.ADMIN ) );

        @SuppressWarnings( "unchecked" )
        DeployPoint deploy = restSerializer.fromRequestBody( request, DeployPoint.class );

        logger.info( "\n\nGot proxy: %s\n\n", deploy );

        ResponseBuilder builder;
        try
        {
            if ( proxyManager.storeDeployPoint( deploy, true ) )
            {
                builder =
                    Response.created( uriInfo.getAbsolutePathBuilder().path( deploy.getName() ).build() );
            }
            else
            {
                builder = Response.status( Status.CONFLICT ).entity( "Repository already exists." );
            }
        }
        catch ( ProxyDataException e )
        {
            logger.error( "Failed to create proxy: %s. Reason: %s", e, e.getMessage() );
            builder = Response.status( Status.INTERNAL_SERVER_ERROR );
        }

        return builder.build();
    }

    @POST
    @Path( "/{name}" )
    @Consumes( { MediaType.APPLICATION_JSON } )
    public Response store( @PathParam( "name" ) final String name )
    {
        SecurityUtils.getSubject().isPermitted( Permission.name( StoreType.repository.name(),
                                                                 Permission.ADMIN ) );

        @SuppressWarnings( "unchecked" )
        DeployPoint deploy = restSerializer.fromRequestBody( request, DeployPoint.class );

        ResponseBuilder builder;
        try
        {
            DeployPoint toUpdate = proxyManager.getDeployPoint( name );
            if ( toUpdate == null )
            {
                toUpdate = deploy;
            }
            else
            {
                toUpdate.setAllowReleases( deploy.isAllowReleases() );
                toUpdate.setAllowSnapshots( deploy.isAllowSnapshots() );
            }

            proxyManager.storeDeployPoint( toUpdate );
            builder = Response.created( uriInfo.getAbsolutePathBuilder().build() );
        }
        catch ( ProxyDataException e )
        {
            logger.error( "Failed to save proxy: %s. Reason: %s", e, e.getMessage() );
            builder = Response.status( Status.INTERNAL_SERVER_ERROR );
        }

        return builder.build();
    }

    @GET
    @Path( "/list" )
    @Produces( { MediaType.APPLICATION_JSON } )
    public Response getAll()
    {
        SecurityUtils.getSubject().isPermitted( Permission.name( StoreType.repository.name(),
                                                                 Permission.ADMIN ) );

        try
        {
            Listing<DeployPoint> listing =
                new Listing<DeployPoint>( proxyManager.getAllDeployPoints() );
            TypeToken<Listing<DeployPoint>> tt = new TypeToken<Listing<DeployPoint>>()
            {};

            return Response.ok().entity( restSerializer.toString( listing, tt.getType() ) ).build();
        }
        catch ( ProxyDataException e )
        {
            logger.error( e.getMessage(), e );
            throw new WebApplicationException( Status.INTERNAL_SERVER_ERROR );
        }
    }

    @GET
    @Path( "/{name}" )
    public Response get( @PathParam( "name" ) final String name )
    {
        SecurityUtils.getSubject().isPermitted( Permission.name( StoreType.repository.name(),
                                                                 Permission.ADMIN ) );

        try
        {
            DeployPoint deploy = proxyManager.getDeployPoint( name );
            logger.info( "Returning repository: %s", deploy );

            if ( deploy == null )
            {
                return Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                return Response.ok().entity( restSerializer.toString( deploy ) ).build();
            }
        }
        catch ( ProxyDataException e )
        {
            logger.error( e.getMessage(), e );
            throw new WebApplicationException( Status.INTERNAL_SERVER_ERROR );
        }
    }

    @DELETE
    @Path( "/{name}" )
    public Response delete( @PathParam( "name" ) final String name )
    {
        SecurityUtils.getSubject().isPermitted( Permission.name( StoreType.repository.name(),
                                                                 Permission.ADMIN ) );

        ResponseBuilder builder;
        try
        {
            proxyManager.deleteRepository( name );
            builder = Response.ok();
        }
        catch ( ProxyDataException e )
        {
            logger.error( e.getMessage(), e );
            builder = Response.status( Status.INTERNAL_SERVER_ERROR );
        }

        return builder.build();
    }

}