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
package org.commonjava.aprox.folo.ftest.content;

import static org.commonjava.aprox.model.core.StoreType.group;
import static org.commonjava.aprox.model.core.StoreType.remote;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.commonjava.aprox.client.core.AproxClientModule;
import org.commonjava.aprox.folo.client.AproxFoloAdminClientModule;
import org.commonjava.aprox.folo.client.AproxFoloContentClientModule;
import org.commonjava.aprox.ftest.core.AbstractAproxFunctionalTest;
import org.commonjava.aprox.model.core.Group;
import org.commonjava.aprox.model.core.HostedRepository;
import org.commonjava.aprox.model.core.RemoteRepository;
import org.junit.Before;

public class AbstractFoloContentManagementTest
    extends AbstractAproxFunctionalTest
{

    protected static final String STORE = "test";

    protected static final String CENTRAL = "central";

    protected static final String PUBLIC = "public";

    @Before
    public void before()
        throws Exception
    {
        final String changelog = "Setup: " + name.getMethodName();
        final HostedRepository hosted =
            this.client.stores()
                       .create( new HostedRepository( STORE ), changelog, HostedRepository.class );

        RemoteRepository central = null;
        if ( !client.stores()
                    .exists( remote, CENTRAL ) )
        {
            central =
                client.stores()
                      .create( new RemoteRepository( CENTRAL, "http://repo.maven.apache.org/maven2/" ), changelog,
                               RemoteRepository.class );
        }
        else
        {
            central = client.stores()
                            .load( remote, CENTRAL, RemoteRepository.class );
        }

        Group g;
        if ( client.stores()
                   .exists( group, PUBLIC ) )
        {
            g = client.stores()
                      .load( group, PUBLIC, Group.class );
        }
        else
        {
            g = client.stores()
                      .create( new Group( PUBLIC ), changelog, Group.class );
        }

        g.setConstituents( Arrays.asList( hosted.getKey(), central.getKey() ) );
        client.stores()
              .update( g, changelog );
    }

    @Override
    protected Collection<AproxClientModule> getAdditionalClientModules()
    {
        return Arrays.<AproxClientModule> asList( new AproxFoloContentClientModule(), new AproxFoloAdminClientModule() );
    }

}