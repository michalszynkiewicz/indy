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
package org.commonjava.indy.core.data;

import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.maven.galley.event.EventMetadata;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.Before;
import org.junit.Test;

public abstract class GroupDataManagerTCK
    extends AbstractProxyDataManagerTCK
{

    private StoreDataManager manager;

    private final ChangeSummary summary = new ChangeSummary( "test-user", "test" );

    @Before
    public void setup()
        throws Exception
    {
        doSetup();
        seedRepositoriesForGroupTests();
    }

    protected void doSetup()
        throws Exception
    {
    }

    protected void seedRepositoriesForGroupTests()
        throws Exception
    {
        manager = getFixtureProvider().getDataManager();

        manager.storeArtifactStore( new RemoteRepository( "central", "http://repo1.maven.apache.org/maven2/" ),
                                       summary, new EventMetadata() );
        manager.storeArtifactStore( new RemoteRepository( "repo2", "http://repo1.maven.org/maven2/" ), summary, new EventMetadata() );
    }

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    public void createAndRetrieveEmptyGroup()
        throws Exception
    {
        final Group grp = new Group( "test" );

        store( grp );

        final Group result = manager.getGroup( grp.getName() );

        assertThat( result, notNullValue() );
        assertThat( result.getName(), equalTo( grp.getName() ) );
        assertThat( result.getConstituents(), anyOf( nullValue(), new BaseMatcher<List>()
        {
            @Override
            public boolean matches( final Object item )
            {
                return ( item instanceof List ) && ( (List) item ).isEmpty();
            }

            @Override
            public void describeTo( final Description description )
            {
                description.appendText( "empty list" );
            }
        } ) );
    }

    private void store( final Group... groups )
        throws Exception
    {
        for ( final Group group : groups )
        {
            manager.storeArtifactStore( group, summary, new EventMetadata() );
        }
    }

    @Test
    public void createAndDeleteGroup()
        throws Exception
    {
        final StoreDataManager manager = getFixtureProvider().getDataManager();

        final Group grp = new Group( "test" );
        store( grp );

        manager.deleteArtifactStore( grp.getKey(), summary );

        final Group result = manager.getGroup( grp.getName() );

        assertThat( result, nullValue() );
    }

    @Test
    public void createAndRetrieveGroupWithTwoConstituents()
        throws Exception
    {
        final StoreDataManager manager = getFixtureProvider().getDataManager();

        final Group grp =
            new Group( "test", new StoreKey( StoreType.remote, "central" ), new StoreKey( StoreType.remote, "repo2" ) );

        store( grp );

        final Group result = manager.getGroup( grp.getName() );

        assertThat( result, notNullValue() );
        assertThat( result.getName(), equalTo( grp.getName() ) );

        final List<StoreKey> repos = result.getConstituents();
        assertThat( repos, notNullValue() );
        assertThat( repos.size(), equalTo( 2 ) );

        assertThat( repos.get( 0 ), equalTo( new StoreKey( StoreType.remote, "central" ) ) );
        assertThat( repos.get( 1 ), equalTo( new StoreKey( StoreType.remote, "repo2" ) ) );
    }

    @Test
    public void createGroupAndRetrieveReposForThatGroupInOrder()
        throws Exception
    {
        final StoreDataManager manager = getFixtureProvider().getDataManager();

        final Group grp =
            new Group( "test", new StoreKey( StoreType.remote, "repo2" ), new StoreKey( StoreType.remote, "central" ) );

        store( grp );

        final List<? extends ArtifactStore> repos = manager.getOrderedConcreteStoresInGroup( grp.getName(), false );

        assertThat( repos, notNullValue() );
        assertThat( repos.size(), equalTo( 2 ) );

        assertThat( repos.get( 0 )
                         .getName(), equalTo( "repo2" ) );
        assertThat( repos.get( 1 )
                         .getName(), equalTo( "central" ) );
    }

    @Test
    public void createGroupAndRetrieveRepositoryConstituents()
        throws Exception
    {
        final StoreDataManager manager = getFixtureProvider().getDataManager();

        final Group grp =
            new Group( "test", new StoreKey( StoreType.remote, "central" ), new StoreKey( StoreType.remote, "repo2" ) );

        store( grp );

        final List<? extends ArtifactStore> result = manager.getOrderedConcreteStoresInGroup( grp.getName(), false );

        assertThat( result, notNullValue() );
        assertThat( result.size(), equalTo( 2 ) );

        ArtifactStore repo = result.get( 0 );
        assertThat( repo, notNullValue() );
        assertThat( repo.getName(), equalTo( "central" ) );

        repo = result.get( 1 );
        assertThat( repo, notNullValue() );
        assertThat( repo.getName(), equalTo( "repo2" ) );
    }

    @Test
    public void createSameGroupTwiceAndRetrieveOne()
        throws Exception
    {
        final StoreDataManager manager = getFixtureProvider().getDataManager();

        final Group grp = new Group( "test" );

        store( grp, grp );

        final List<? extends Group> result = manager.getAllGroups();

        assertThat( result, notNullValue() );
        assertThat( result.size(), equalTo( 1 ) );
    }

    @Test
    public void createTwoGroupsAndRetrieveBoth()
        throws Exception
    {
        final StoreDataManager manager = getFixtureProvider().getDataManager();

        final Group grp = new Group( "test" );
        final Group grp2 = new Group( "test2" );

        store( grp, grp2 );

        final List<? extends Group> result = manager.getAllGroups();

        assertThat( result, notNullValue() );
        assertThat( result.size(), equalTo( 2 ) );

        Collections.sort( result, new Comparator<Group>()
        {
            @Override
            public int compare( final Group g1, final Group g2 )
            {
                return g1.getName()
                         .compareTo( g2.getName() );
            }
        } );

        Group g = result.get( 0 );
        assertThat( g.getName(), equalTo( grp.getName() ) );

        g = result.get( 1 );
        assertThat( g.getName(), equalTo( grp2.getName() ) );
    }

}
