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
package org.commonjava.aprox.core.live.rest.access;

import static org.apache.commons.io.FileUtils.forceDelete;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;

import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.commonjava.aprox.core.data.ProxyDataException;
import org.commonjava.aprox.core.live.AbstractAProxLiveTest;
import org.commonjava.aprox.core.live.fixture.ProxyConfigProvider;
import org.commonjava.aprox.core.model.Group;
import org.commonjava.aprox.core.model.Repository;
import org.commonjava.aprox.core.model.StoreKey;
import org.commonjava.aprox.core.model.StoreType;
import org.commonjava.web.test.fixture.TestWarArchiveBuilder;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith( Arquillian.class )
public class GroupAccessResourceLiveTest
    extends AbstractAProxLiveTest
{

    private static final String BASE_URL = "http://localhost:8080/test/api/1.0/group/test/";

    private static File repoRoot;

    @Deployment
    public static WebArchive createWar()
    {
        return new TestWarArchiveBuilder( GroupAccessResourceLiveTest.class ).withExtraClasses( AbstractAProxLiveTest.class,
                                                                                                ProxyConfigProvider.class )
                                                                             .build();
    }

    @BeforeClass
    public static void setRepoRootDir()
        throws IOException
    {
        repoRoot = File.createTempFile( "repo.root.", ".dir" );
        System.setProperty( ProxyConfigProvider.REPO_ROOT_DIR, repoRoot.getAbsolutePath() );
    }

    @AfterClass
    public static void clearRepoRootDir()
        throws IOException
    {
        if ( repoRoot != null && repoRoot.exists() )
        {
            forceDelete( repoRoot );
        }
    }

    @Before
    public void setupTest()
        throws ProxyDataException
    {
        proxyManager.storeRepository( new Repository( "dummy", "http://www.nowhere.com/" ) );
        proxyManager.storeRepository( new Repository( "central", "http://repo1.maven.apache.org/maven2/" ) );

        proxyManager.storeGroup( new Group( "test", new StoreKey( StoreType.repository, "dummy" ),
                                            new StoreKey( StoreType.repository, "central" ) ) );
    }

    @Test
    public void retrievePOMFromRepositoryGroupWithDummyFirstRepository()
        throws ClientProtocolException, IOException
    {
        final String response =
            getString( BASE_URL + "org/apache/maven/maven-model/3.0.3/maven-model-3.0.3.pom", HttpStatus.SC_OK );
        assertThat( response.contains( "<artifactId>maven-model</artifactId>" ), equalTo( true ) );
    }

}