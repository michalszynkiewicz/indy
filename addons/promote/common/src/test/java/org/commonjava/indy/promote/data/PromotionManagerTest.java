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
package org.commonjava.indy.promote.data;

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.content.ContentGenerator;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.DownloadManager;
import org.commonjava.indy.content.IndyLocationExpander;
import org.commonjava.indy.core.content.DefaultContentManager;
import org.commonjava.indy.core.content.DefaultDownloadManager;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.mem.data.MemoryStoreDataManager;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.promote.conf.PromoteConfig;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.commonjava.indy.promote.validate.PromoteValidationsManager;
import org.commonjava.indy.promote.validate.PromotionValidationTools;
import org.commonjava.indy.promote.validate.PromotionValidator;
import org.commonjava.indy.promote.validate.ValidationRuleParser;
import org.commonjava.indy.subsys.datafile.DataFileManager;
import org.commonjava.indy.subsys.datafile.change.DataFileEventManager;
import org.commonjava.indy.subsys.template.ScriptEngine;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.maven.rel.MavenModelProcessor;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.testing.maven.GalleyMavenFixture;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

public class PromotionManagerTest
{

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ContentManager contentManager;

    private DownloadManager downloadManager;

    private StoreDataManager storeManager;

    private GalleyMavenFixture galleyParts;

    private PromotionManager manager;

    private DataFileManager dataManager;

    private PromoteValidationsManager validationsManager;

    private PromotionValidator validator;

    private Executor executor;

    @Before
    public void setup()
            throws Exception
    {
        galleyParts = new GalleyMavenFixture( true, temp );
        galleyParts.initMissingComponents();

        storeManager = new MemoryStoreDataManager( true );

        downloadManager = new DefaultDownloadManager( storeManager, galleyParts.getTransferManager(),
                                                      new IndyLocationExpander( storeManager ) );

        contentManager = new DefaultContentManager( storeManager, downloadManager, new IndyObjectMapper( true ),
                                                    Collections.<ContentGenerator>emptySet() );

        dataManager = new DataFileManager( temp.newFolder( "data" ), new DataFileEventManager() );
        validationsManager = new PromoteValidationsManager( dataManager, new PromoteConfig(),
                                                            new ValidationRuleParser( new ScriptEngine(),
                                                                                      new IndyObjectMapper( true ) ) );

        MavenModelProcessor modelProcessor = new MavenModelProcessor();
        validator = new PromotionValidator( validationsManager,
                                            new PromotionValidationTools( contentManager, storeManager,
                                                                          galleyParts.getPomReader(),
                                                                          galleyParts.getMavenMetadataReader(),
                                                                          modelProcessor, galleyParts.getTypeMapper(),
                                                                          galleyParts.getTransferManager() ) );
        manager = new PromotionManager( validator, contentManager, downloadManager, storeManager );

        executor = Executors.newCachedThreadPool();
    }

    /**
     * On collision, the promotion manager should skip the second file to be promoted (instead of overwriting the
     * existing one). This assumes no overwrite attribute is available for setting in the promotion request (or that
     * it is available but isn't set...and defaults to false).
     * @throws Exception
     */
    @Test
    public void promoteAllByPath_CollidingPaths_VerifySecondSkipped()
            throws Exception
    {
        final HostedRepository source1 = new HostedRepository( "source1" );
        final HostedRepository source2 = new HostedRepository( "source2" );
        storeManager.storeArtifactStore( source1, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );
        storeManager.storeArtifactStore( source2, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        String originalString = "This is a test";

        final String path = "/path/path";
        contentManager.store( source1, path, new ByteArrayInputStream( originalString.getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        contentManager.store( source2, path, new ByteArrayInputStream( "This is another test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        PathsPromoteResult result =
                manager.promotePaths( new PathsPromoteRequest( source1.getKey(), target.getKey(), path ) );

        assertThat( result.getRequest().getSource(), equalTo( source1.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        Set<String> pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        Set<String> skipped = result.getSkippedPaths();
        assertThat( skipped == null || skipped.isEmpty(), equalTo( true ) );

        Set<String> completed = result.getCompletedPaths();
        assertThat( completed, notNullValue() );
        assertThat( completed.size(), equalTo( 1 ) );

        assertThat( result.getError(), nullValue() );

        Transfer ref = downloadManager.getStorageReference( target, path );
        assertThat( ref.exists(), equalTo( true ) );
        try (InputStream in = ref.openInputStream())
        {
            String value = IOUtils.toString( in );
            assertThat( value, equalTo( originalString ) );
        }

        result = manager.promotePaths( new PathsPromoteRequest( source1.getKey(), target.getKey(), path ) );

        assertThat( result.getRequest().getSource(), equalTo( source1.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        skipped = result.getSkippedPaths();
        assertThat( skipped, notNullValue() );
        assertThat( skipped.size(), equalTo( 1 ) );

        completed = result.getCompletedPaths();
        assertThat( completed == null || completed.isEmpty(), equalTo( true ) );

        assertThat( result.getError(), nullValue() );

        ref = downloadManager.getStorageReference( target, path );
        assertThat( ref.exists(), equalTo( true ) );
        try (InputStream in = ref.openInputStream())
        {
            String value = IOUtils.toString( in );
            assertThat( value, equalTo( originalString ) );
        }
    }

    @Test
    public void promoteAllByPath_RaceToPromote_FirstLocksTargetStore()
            throws Exception
    {
        Random rand = new Random();
        final HostedRepository[] sources = { new HostedRepository( "source1" ), new HostedRepository( "source2" ) };
        final String[] paths = { "/path/path1", "/path/path2", "/path3", "/path/path/4" };
        Stream.of( sources ).forEach( ( source ) -> {
            try
            {
                storeManager.storeArtifactStore( source, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                                 new EventMetadata() );

                Stream.of( paths ).forEach( ( path ) -> {
                    byte[] buf = new byte[1024 * 1024 * 2];
                    rand.nextBytes( buf );
                    try
                    {
                        contentManager.store( source, path, new ByteArrayInputStream( buf ), TransferOperation.UPLOAD,
                                              new EventMetadata() );
                    }
                    catch ( IndyWorkflowException e )
                    {
                        e.printStackTrace();
                        Assert.fail( "failed to store generated file to: " + source + path );
                    }
                } );
            }
            catch ( IndyDataException e )
            {
                e.printStackTrace();
                Assert.fail( "failed to store hosted repository: " + source );
            }
        } );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        PathsPromoteResult[] results = new PathsPromoteResult[2];
        CountDownLatch cdl = new CountDownLatch( 2 );

        AtomicInteger counter = new AtomicInteger( 0 );
        Stream.of( sources ).forEach( ( source ) -> {
            int idx = counter.getAndIncrement();
            executor.execute( () -> {
                try
                {
                    results[idx] =
                            manager.promotePaths( new PathsPromoteRequest( source.getKey(), target.getKey(), paths ) );
                }
                catch ( Exception e )
                {
                    e.printStackTrace();
                    Assert.fail( "Promotion from source: " + source + " failed." );
                }
                finally
                {
                    cdl.countDown();
                }
            } );

            try
            {
                Thread.sleep( 25 );
            }
            catch ( InterruptedException e )
            {
                Assert.fail( "Test interrupted" );
            }
        } );

        assertThat( "Promotions failed to finish.", cdl.await( 30, TimeUnit.SECONDS ), equalTo( true ) );

        // first one should succeed.
        PathsPromoteResult result = results[0];
        assertThat( result.getRequest().getSource(), equalTo( sources[0].getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        Set<String> pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        Set<String> skipped = result.getSkippedPaths();
        assertThat( skipped == null || skipped.isEmpty(), equalTo( true ) );

        Set<String> completed = result.getCompletedPaths();
        assertThat( completed, notNullValue() );
        assertThat( completed.size(), equalTo( paths.length ) );

        assertThat( result.getError(), nullValue() );

        Stream.of( paths ).forEach( ( path ) -> {
            HostedRepository src = sources[0];
            Transfer sourceRef = downloadManager.getStorageReference( src, path );
            Transfer targetRef = downloadManager.getStorageReference( target, path );
            assertThat( targetRef.exists(), equalTo( true ) );
            try (InputStream sourceIn = sourceRef.openInputStream();
                 InputStream targetIn = targetRef.openInputStream())
            {
                int s = -1, t = -1;
                while ( ( s = sourceIn.read() ) == ( t = targetIn.read() ) )
                {
                    if ( s == -1 )
                    {
                        break;
                    }
                }

                if ( s != -1 && s != t )
                {
                    Assert.fail( path + " doesn't match between source: " + src + " and target: " + target );
                }
            }
            catch ( IOException e )
            {
                e.printStackTrace();
                Assert.fail( "Failed to compare contents of: " + path + " between source: " + src + " and target: "
                                     + target );
            }
        } );

        // second one should be completely skipped.
        result = results[1];
        assertThat( result.getRequest().getSource(), equalTo( sources[1].getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        skipped = result.getSkippedPaths();
        assertThat( skipped, notNullValue() );
        assertThat( skipped.size(), equalTo( paths.length ) );

        completed = result.getCompletedPaths();
        assertThat( completed == null || completed.isEmpty(), equalTo( true ) );

        assertThat( result.getError(), nullValue() );
    }

    @Test
    public void promoteAllByPath_PushTwoArtifactsToHostedRepo_VerifyCopiedToOtherHostedRepo()
            throws Exception
    {
        final HostedRepository source = new HostedRepository( "source" );
        storeManager.storeArtifactStore( source, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final String first = "/first/path";
        final String second = "/second/path";
        contentManager.store( source, first, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        contentManager.store( source, second, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final PathsPromoteResult result =
                manager.promotePaths( new PathsPromoteRequest( source.getKey(), target.getKey() ) );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        final Set<String> pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        final Set<String> completed = result.getCompletedPaths();
        assertThat( completed, notNullValue() );
        assertThat( completed.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        Transfer ref = downloadManager.getStorageReference( target, first );
        assertThat( ref.exists(), equalTo( true ) );

        ref = downloadManager.getStorageReference( target, second );
        assertThat( ref.exists(), equalTo( true ) );
    }

    @Test
    public void promoteAllByPath_PushTwoArtifactsToHostedRepo_DryRun_VerifyPendingPathsPopulated()
            throws Exception
    {
        final HostedRepository source = new HostedRepository( "source" );
        storeManager.storeArtifactStore( source, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final String first = "/first/path";
        final String second = "/second/path";
        contentManager.store( source, first, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        contentManager.store( source, second, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final PathsPromoteResult result =
                manager.promotePaths( new PathsPromoteRequest( source.getKey(), target.getKey() ).setDryRun( true ) );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        final Set<String> completed = result.getCompletedPaths();
        assertThat( completed == null || completed.isEmpty(), equalTo( true ) );

        final Set<String> pending = result.getPendingPaths();
        assertThat( pending, notNullValue() );
        assertThat( pending.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        Transfer ref = downloadManager.getStorageReference( target, first );
        assertThat( ref.exists(), equalTo( false ) );

        ref = downloadManager.getStorageReference( target, second );
        assertThat( ref.exists(), equalTo( false ) );
    }

    @Test
    public void promoteAllByPath_PurgeSource_PushTwoArtifactsToHostedRepo_VerifyCopiedToOtherHostedRepo()
            throws Exception
    {
        final HostedRepository source = new HostedRepository( "source" );
        storeManager.storeArtifactStore( source, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final String first = "/first/path";
        final String second = "/second/path";
        contentManager.store( source, first, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        contentManager.store( source, second, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final PathsPromoteResult result = manager.promotePaths(
                new PathsPromoteRequest( source.getKey(), target.getKey() ).setPurgeSource( true ) );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        final Set<String> pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        final Set<String> completed = result.getCompletedPaths();
        assertThat( completed, notNullValue() );
        assertThat( completed.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        Transfer ref = downloadManager.getStorageReference( target, first );
        assertThat( ref.exists(), equalTo( true ) );

        ref = downloadManager.getStorageReference( target, second );
        assertThat( ref.exists(), equalTo( true ) );

        // source artifacts should be deleted.
        ref = downloadManager.getStorageReference( source, first );
        assertThat( ref.exists(), equalTo( false ) );

        ref = downloadManager.getStorageReference( source, second );
        assertThat( ref.exists(), equalTo( false ) );
    }

    @Test
    public void rollback_PushTwoArtifactsToHostedRepo_PromoteSuccessThenRollback()
            throws Exception
    {
        final HostedRepository source = new HostedRepository( "source" );
        storeManager.storeArtifactStore( source, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final String first = "/first/path";
        final String second = "/second/path";
        contentManager.store( source, first, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        contentManager.store( source, second, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        PathsPromoteResult result = manager.promotePaths( new PathsPromoteRequest( source.getKey(), target.getKey() ) );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        Set<String> pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        Set<String> completed = result.getCompletedPaths();
        assertThat( completed, notNullValue() );
        assertThat( completed.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        result = manager.rollbackPathsPromote( result );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        completed = result.getCompletedPaths();
        assertThat( completed == null || completed.isEmpty(), equalTo( true ) );

        pending = result.getPendingPaths();
        assertThat( pending, notNullValue() );
        assertThat( pending.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        Transfer ref = downloadManager.getStorageReference( target, first );
        assertThat( ref.exists(), equalTo( false ) );

        ref = downloadManager.getStorageReference( target, second );
        assertThat( ref.exists(), equalTo( false ) );
    }

    @Test
    public void rollback_PurgeSource_PushTwoArtifactsToHostedRepo_PromoteSuccessThenRollback_VerifyContentInSource()
            throws Exception
    {
        final HostedRepository source = new HostedRepository( "source" );
        storeManager.storeArtifactStore( source, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        final String first = "/first/path";
        final String second = "/second/path";
        contentManager.store( source, first, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        contentManager.store( source, second, new ByteArrayInputStream( "This is a test".getBytes() ),
                              TransferOperation.UPLOAD, new EventMetadata() );

        final HostedRepository target = new HostedRepository( "target" );
        storeManager.storeArtifactStore( target, new ChangeSummary( ChangeSummary.SYSTEM_USER, "test setup" ),
                                         new EventMetadata() );

        PathsPromoteResult result = manager.promotePaths(
                new PathsPromoteRequest( source.getKey(), target.getKey() ).setPurgeSource( true ) );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        Set<String> pending = result.getPendingPaths();
        assertThat( pending == null || pending.isEmpty(), equalTo( true ) );

        Set<String> completed = result.getCompletedPaths();
        assertThat( completed, notNullValue() );
        assertThat( completed.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        result = manager.rollbackPathsPromote( result );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest().getTarget(), equalTo( target.getKey() ) );

        completed = result.getCompletedPaths();
        assertThat( completed == null || completed.isEmpty(), equalTo( true ) );

        pending = result.getPendingPaths();
        assertThat( pending, notNullValue() );
        assertThat( pending.size(), equalTo( 2 ) );

        assertThat( result.getError(), nullValue() );

        Transfer ref = downloadManager.getStorageReference( target, first );
        assertThat( ref.exists(), equalTo( false ) );

        ref = downloadManager.getStorageReference( target, second );
        assertThat( ref.exists(), equalTo( false ) );

        ref = downloadManager.getStorageReference( source, first );
        assertThat( ref.exists(), equalTo( true ) );

        ref = downloadManager.getStorageReference( source, second );
        assertThat( ref.exists(), equalTo( true ) );
    }
}
