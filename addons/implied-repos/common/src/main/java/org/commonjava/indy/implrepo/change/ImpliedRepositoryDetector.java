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
package org.commonjava.indy.implrepo.change;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.implrepo.ImpliedReposException;
import org.commonjava.indy.implrepo.conf.ImpliedRepoConfig;
import org.commonjava.indy.implrepo.data.ImpliedRepoMetadataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
import org.commonjava.maven.atlas.ident.util.JoinString;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.event.FileStorageEvent;
import org.commonjava.maven.galley.maven.GalleyMavenException;
import org.commonjava.maven.galley.maven.model.view.MavenPomView;
import org.commonjava.maven.galley.maven.model.view.RepositoryView;
import org.commonjava.maven.galley.maven.parse.MavenPomReader;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImpliedRepositoryDetector
{
    public static final String IMPLIED_REPOS_DETECTION = "implied-repos-detector";

    public static final String IMPLIED_BY_POM_TRANSFER = "pom-transfer";

    public static final String IMPLIED_REPOS = "implied-repositories";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private MavenPomReader pomReader;

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private ImpliedRepoMetadataManager metadataManager;

    @Inject
    private ImpliedRepoConfig config;

    protected ImpliedRepositoryDetector()
    {
    }

    public ImpliedRepositoryDetector( final MavenPomReader pomReader, final StoreDataManager storeManager,
                                      final ImpliedRepoMetadataManager metadataManager, final ImpliedRepoConfig config )
    {
        this.pomReader = pomReader;
        this.storeManager = storeManager;
        this.metadataManager = metadataManager;
        this.config = config;
    }

    public void detectRepos( @Observes final FileStorageEvent event )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Implied-repository processing is not enabled." );
            return;
        }

        try
        {
            logger.debug( "STARTED Processing: {}", event );
            final ImplicationsJob job = new ImplicationsJob( event );
            if ( !initJob( job ) )
            {
                return;
            }

            addImpliedRepositories( job );

            if ( job.implied != null && !job.implied.isEmpty() )
            {
                // Store in source remote repo metadata for future groups.
                if ( !addImpliedMetadata( job ) )
                {
                    return;
                }

                // Update existing groups
                if ( !updateExistingGroups( job ) )
                {
                    return;
                }
            }
        }
        catch ( Throwable error )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.error( String.format( "Implied-repository maintenance failed: %s", error.getMessage() ), error );
        }
        finally
        {
            logger.debug( "FINISHED Processing: {}", event );
        }
    }

    private boolean initJob( final ImplicationsJob job )
    {
        switch ( job.event.getType() )
        {
            case DOWNLOAD:
            case UPLOAD:
                break;

            default:
                // we're not interested in these.
                return false;
        }

        final Transfer transfer = job.transfer;
        if ( !transfer.getPath()
                      .endsWith( ".pom" ) )
        {
            return false;
        }

        final Location location = transfer.getLocation();
        if ( !( location instanceof KeyedLocation ) )
        {
            return false;
        }

        final StoreKey key = ( (KeyedLocation) location ).getKey();
        try
        {
            job.store = storeManager.getArtifactStore( key );
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Cannot retrieve artifact store for: %s. Failed to process implied repositories.",
                                         key ), e );
        }

        if ( job.store == null )
        {
            return false;
        }

        job.pathInfo = ArtifactPathInfo.parse( transfer.getPath() );

        if ( job.pathInfo == null )
        {
            return false;
        }

        try
        {
            logger.debug( "Parsing: {}", transfer );

            job.pomView = pomReader.readLocalPom( job.pathInfo.getProjectId(), transfer, MavenPomView.ALL_PROFILES );
        }
        catch ( final GalleyMavenException e )
        {
            logger.error( String.format( "Cannot parse: %s from: %s. Failed to process implied repositories.",
                                         job.pathInfo.getProjectId(), transfer ), e );
        }

        return job.pomView != null;

    }

    private boolean updateExistingGroups( final ImplicationsJob job )
    {
        final StoreKey key = job.store.getKey();
        boolean anyChanged = false;
        try
        {
            final Set<Group> groups = storeManager.getGroupsContaining( key );
            logger.debug( "{} groups contain: {}\n  {}", groups.size(), key, new JoinString( "\n  ", groups ) );
            if ( groups != null )
            {
                final String message =
                    String.format( "Adding repositories implied by: %s\n\n  %s", key,
                                   StringUtils.join( job.implied, "\n  " ) );

                final ChangeSummary summary = new ChangeSummary( ChangeSummary.SYSTEM_USER, message );
                for ( final Group g : groups )
                {
                    Group group = g.copyOf();

                    boolean changed = false;
                    for ( final ArtifactStore implied : job.implied )
                    {
                        boolean groupChanged = group.addConstituent( implied );
                        changed = groupChanged || changed;

                        logger.debug( "After attempting to add: {} to group: {}, changed status is: {}", implied, group, changed );
                    }

                    if ( changed )
                    {
                        storeManager.storeArtifactStore( group,
                                                         summary,
                                                         false,
                                                         false,
                                                         new EventMetadata().set( StoreDataManager.EVENT_ORIGIN,
                                                                                  IMPLIED_REPOS_DETECTION )
                                                                            .set( IMPLIED_REPOS, job.implied ) );
                    }

                    anyChanged = changed || anyChanged;
                }
            }
        }
        catch ( final IndyDataException e )
        {
            logger.error( "Failed to lookup groups containing: " + key, e );
        }

        return anyChanged;
    }

    private boolean addImpliedMetadata( final ImplicationsJob job )
    {
        try
        {
            logger.debug( "Adding implied-repo metadata to: {} and {}", job.store, new JoinString( ", ", job.implied ) );
            metadataManager.addImpliedMetadata( job.store, job.implied );
            return true;
        }
        catch ( final ImpliedReposException e )
        {
            logger.error( "Failed to store list of implied stores in: " + job.store.getKey(), e );
        }

        return false;
    }

    private void addImpliedRepositories( final ImplicationsJob job )
    {
        job.implied = new ArrayList<ArtifactStore>();

        logger.debug( "Retrieving repository/pluginRepository declarations from:\n  {}",
                      new JoinString( "\n  ", job.pomView.getDocRefStack() ) );

        final List<List<RepositoryView>> repoLists =
            Arrays.asList( job.pomView.getAllRepositories(), job.pomView.getAllPluginRepositories() );

        for ( final List<RepositoryView> repos : repoLists )
        {
            if ( repos == null || repos.isEmpty() )
            {
                continue;
            }

            for ( final RepositoryView repo : repos )
            {
                final ProjectVersionRef gav = job.pathInfo.getProjectId();
                try
                {
                    if ( config.isBlacklisted( repo.getUrl() ) )
                    {
                        logger.debug( "Discarding blacklisted repository: {}", repo );
                        continue;
                    }
                    else if ( !config.isIncludeSnapshotRepos() && !repo.isReleasesEnabled() )
                    {
                        logger.debug( "Discarding snapshot repository: {}", repo );
                        continue;
                    }
                }
                catch ( final MalformedURLException e )
                {
                    logger.error( String.format( "Cannot add implied remote repo: %s from: %s (transfer: %s). Failed to check if repository is blacklisted.",
                                                 repo.getUrl(), gav, job.transfer ), e );
                }

                logger.debug( "Detected POM-declared repository: {}", repo );
                RemoteRepository rr = storeManager.findRemoteRepository( repo.getUrl() );
                if ( rr == null )
                {
                    logger.debug( "Creating new RemoteRepository for: {}", repo );

                    rr = new RemoteRepository( formatId( repo.getId() ), repo.getUrl() );

                    rr.setAllowSnapshots( repo.isSnapshotsEnabled() );
                    rr.setAllowReleases( repo.isReleasesEnabled() );

                    rr.setDescription( "Implicitly created repo for: " + repo.getName() + " (" + repo.getId()
                        + ") from repository declaration in POM: " + gav );

                    final String changelog =
                        String.format( "Adding remote repository: %s (url: %s, name: %s), which is implied by the POM: %s (at: %s/%s)",
                                       repo.getId(), repo.getUrl(), repo.getName(), gav, job.transfer.getLocation()
                                                                                                     .getUri(),
                                       job.transfer.getPath() );

                    final ChangeSummary summary = new ChangeSummary( ChangeSummary.SYSTEM_USER, changelog );
                    try
                    {
                        final boolean result =
                            storeManager.storeArtifactStore( rr,
                                                             summary,
                                                             true,
                                                             false,
                                                             new EventMetadata().set( StoreDataManager.EVENT_ORIGIN,
                                                                                      IMPLIED_REPOS_DETECTION )
                                                                                .set( IMPLIED_BY_POM_TRANSFER,
                                                                                      job.transfer ) );

                        logger.debug( "Stored new RemoteRepository: {}. (successful? {})", rr, result );
                        job.implied.add( rr );
                    }
                    catch ( final IndyDataException e )
                    {
                        logger.error( String.format( "Cannot add implied remote repo: %s from: %s (transfer: %s). Failed to store new remote repository.",
                                                     repo.getUrl(), gav, job.transfer ), e );
                    }
                }
                else
                {
                    logger.debug( "Found existing RemoteRepository: {}", rr );
                }
            }
        }
    }

    protected String formatId( final String id )
    {
        //        return "implied-" + repo.getId() + "-" + formatNow();
        return "i-" + id.replaceAll( "[^\\p{Alnum}]", "-" );
    }

    //    private String formatNow()
    //    {
    //        return new SimpleDateFormat( "yyyyMMdd_HHmm" ).format( new Date() );
    //    }

    public class ImplicationsJob
    {
        private final FileStorageEvent event;

        private final Transfer transfer;

        private ArtifactStore store;

        private MavenPomView pomView;

        private ArtifactPathInfo pathInfo;

        private ArrayList<ArtifactStore> implied;

        public ImplicationsJob( final FileStorageEvent event )
        {
            this.event = event;
            this.transfer = event.getTransfer();
        }

    }

}
