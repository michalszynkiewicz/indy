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
package org.commonjava.indy.core.bind.jaxrs;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.util.JaxRsRequestHelper;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.core.bind.jaxrs.util.TransferStreamingOutput;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.util.HttpUtils;
import org.commonjava.indy.util.AcceptInfo;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.indy.util.ApplicationHeader;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.indy.util.UriFormatter;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.function.Supplier;

import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatOkResponseWithEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponse;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponseFromMetadata;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.setInfoHeaders;
import static org.commonjava.indy.core.ctl.ContentController.LISTING_HTML_FILE;

public class ContentAccessHandler
        implements IndyResources
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ContentController contentController;

    @Inject
    private UriFormatter uriFormatter;

    @Inject
    private JaxRsRequestHelper jaxRsRequestHelper;

    protected ContentAccessHandler()
    {
    }

    public ContentAccessHandler( final ContentController controller, final UriFormatter uriFormatter,
                                 JaxRsRequestHelper jaxRsRequestHelper )
    {
        this.contentController = controller;
        this.uriFormatter = uriFormatter;
        this.jaxRsRequestHelper = jaxRsRequestHelper;
    }

    public Response doCreate( final String type, final String name, final String path, final HttpServletRequest request,
                              EventMetadata eventMetadata, Supplier<URI> uriBuilder )
    {
        final StoreType st = StoreType.get( type );
        StoreKey sk = new StoreKey( st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        Response response = null;
        final Transfer transfer;
        try
        {
            transfer =
                    contentController.store( new StoreKey( st, name ), path, request.getInputStream(), eventMetadata );

            final StoreKey storageKey = LocationUtils.getKey( transfer );
            logger.info( "Key for storage location: {}", storageKey );

            final URI uri = uriBuilder.get();

            response = Response.created( uri ).build();
        }
        catch ( final IndyWorkflowException | IOException e )
        {
            logger.error( String.format( "Failed to upload: %s to: %s. Reason: %s", path, name, e.getMessage() ), e );

            response = formatResponse( e );
        }

        return response;
    }

    public Response doDelete( String type, String name, String path, EventMetadata eventMetadata )
    {
        final StoreType st = StoreType.get( type );
        StoreKey sk = new StoreKey( st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        Response response;
        try
        {
            final ApplicationStatus result = contentController.delete( st, name, path, eventMetadata );
            response = Response.status( result.code() ).build();
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error(
                    String.format( "Failed to delete artifact: %s from: %s. Reason: %s", path, name, e.getMessage() ),
                    e );
            response = formatResponse( e );
        }
        return response;
    }

    public Response doHead( String type, String name, String path, Boolean cacheOnly, String baseUri,
                            HttpServletRequest request, EventMetadata eventMetadata )
    {
        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );

        Response response = null;

        if ( path == null || path.equals( "" ) || path.endsWith( "/" ) || path.endsWith( LISTING_HTML_FILE ) )
        {
            try
            {
                logger.info( "Getting listing at: {}", path );
                final String content =
                        contentController.renderListing( acceptInfo.getBaseAccept(), sk, path, baseUri, uriFormatter );

                response = Response.ok()
                                   .header( ApplicationHeader.content_type.key(), acceptInfo.getRawAccept() )
                                   .header( ApplicationHeader.content_length.key(), Long.toString( content.length() ) )
                                   .header( ApplicationHeader.last_modified.key(),
                                            HttpUtils.formatDateHeader( new Date() ) )
                                   .build();
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error(
                        String.format( "Failed to list content: %s from: %s. Reason: %s", path, name, e.getMessage() ),
                        e );
                response = formatResponse( e );
            }
        }
        else
        {
            try
            {
                Transfer item;
                if ( Boolean.TRUE.equals( cacheOnly ) )
                {
                    logger.info( "[CACHE-ONLY] Checking existence of: {}:{}", sk, path );
                    item = contentController.getTransfer( sk, path, TransferOperation.DOWNLOAD );
                    logger.debug( "Got cache-only transfer reference: {}", item );
                }
                else
                {
                    logger.info( "Retrieving: {}:{} for existence test", sk, path );
                    item = contentController.get( sk, path, eventMetadata );
                    logger.debug( "Got retrieved transfer reference: {}", item );
                }

                if ( item == null || !item.exists() )
                {
                    if ( StoreType.remote == st )
                    {
                        final HttpExchangeMetadata metadata = contentController.getHttpMetadata( sk, path );
                        if ( metadata != null )
                        {
                            response = formatResponseFromMetadata( metadata );
                        }
                    }

                    if ( response == null )
                    {
                        response = Response.status( Status.NOT_FOUND ).build();
                    }
                }
                else
                {
                    final ResponseBuilder builder = Response.ok();
                    setInfoHeaders( builder, item, sk, path, true, contentController.getContentType( path ),
                                    contentController.getHttpMetadata( sk, path ) );

                    response = builder.build();
                }
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to download artifact: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = formatResponse( e );
            }
        }
        return response;
    }

    public Response doGet( String type, String name, String path, String baseUri, HttpServletRequest request,
                           EventMetadata eventMetadata )
    {
        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );
        final String standardAccept = ApplicationContent.getStandardAccept( acceptInfo.getBaseAccept() );

        Response response = null;

        logger.info(
                "GET path: '{}' (RAW: '{}')\nIn store: '{}'\nUser accept header is: '{}'\nStandard accept header for that is: '{}'",
                path, request.getPathInfo(), sk, acceptInfo.getRawAccept(), standardAccept );

        if ( path == null || path.equals( "" ) || request.getPathInfo().endsWith( "/" ) || path.endsWith(
                LISTING_HTML_FILE ) )
        {
            try
            {
                logger.info( "Getting listing at: {}", path );
                final String content =
                        contentController.renderListing( standardAccept, st, name, path, baseUri, uriFormatter );

                response = formatOkResponseWithEntity( content, acceptInfo.getRawAccept() );
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to render content listing: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = formatResponse( e );
            }
        }
        else
        {
            try
            {
                final Transfer item = contentController.get( sk, path, eventMetadata );

                if ( item == null || !item.exists() )
                {
                    response = handleMissingContentQuery( sk, path );
                }
                else if ( item.isDirectory() || ( path.endsWith( "index.html" ) ) )
                {
                    try
                    {
                        item.delete( false );

                        logger.info( "Getting listing at: {}", path + "/" );
                        final String content =
                                contentController.renderListing( standardAccept, st, name, path + "/", baseUri,
                                                                 uriFormatter );

                        response = formatOkResponseWithEntity( content, acceptInfo.getRawAccept() );
                    }
                    catch ( final IndyWorkflowException | IOException e )
                    {
                        logger.error(
                                String.format( "Failed to render content listing: %s from: %s. Reason: %s", path, name,
                                               e.getMessage() ), e );
                        response = formatResponse( e );
                    }
                }
                else
                {
                    final ResponseBuilder builder = Response.ok( new TransferStreamingOutput( item, eventMetadata ) );
                    setInfoHeaders( builder, item, sk, path, false, contentController.getContentType( path ),
                                    contentController.getHttpMetadata( sk, path ) );

                    response = builder.build();
                }
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to download artifact: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = formatResponse( e );
            }
        }

        return response;
    }

    private Response handleMissingContentQuery( StoreKey sk, String path )
    {
        Response response = null;

        logger.trace( "Transfer not found: {}/{}", sk, path );
        if ( StoreType.remote == sk.getType() )
        {
            logger.trace( "Transfer was from remote repo. Trying to get HTTP metadata for: {}/{}", sk, path );
            try
            {
                final HttpExchangeMetadata metadata = contentController.getHttpMetadata( sk, path );
                if ( metadata != null )
                {
                    logger.trace( "Using HTTP metadata to formulate response status for: {}/{}", sk, path );
                    response = formatResponseFromMetadata( metadata );
                }
                else
                {
                    logger.trace( "No HTTP metadata found!" );
                }
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Error retrieving status metadata for: %s from: %s. Reason: %s", path,
                                             sk.getName(), e.getMessage() ), e );
                response = formatResponse( e );
            }
        }

        if ( response == null )
        {
            response = Response.status( Status.NOT_FOUND ).build();
        }

        return response;
    }

}
