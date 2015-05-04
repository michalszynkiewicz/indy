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
package org.commonjava.aprox.core.inject;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.commonjava.aprox.core.conf.DefaultAproxConfiguration;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.junit.Test;

public class ExpiringMemoryNotFoundCacheTest
{

    @Test
    public void expireUsingConfiguredValue()
        throws Exception
    {
        final DefaultAproxConfiguration config = new DefaultAproxConfiguration();
        config.setNotFoundCacheTimeoutSeconds( 1 );

        final ExpiringMemoryNotFoundCache nfc = new ExpiringMemoryNotFoundCache( config );

        final ConcreteResource res = new ConcreteResource( new SimpleLocation( "test:uri" ), "/path/to/expired/object" );

        nfc.addMissing( res );
        assertThat( nfc.isMissing( res ), equalTo( true ) );

        Thread.sleep( TimeUnit.SECONDS.toMillis( 1 ) );

        assertThat( nfc.isMissing( res ), equalTo( false ) );

        final Set<String> locMissing = nfc.getMissing( res.getLocation() );
        assertThat( locMissing == null || locMissing.isEmpty(), equalTo( true ) );

        final Map<Location, Set<String>> allMissing = nfc.getAllMissing();
        assertThat( allMissing == null || allMissing.isEmpty(), equalTo( true ) );
    }

    @Test
    public void expireUsingConfiguredValue_DirectCheckDoesntAffectAggregateChecks()
        throws Exception
    {
        final DefaultAproxConfiguration config = new DefaultAproxConfiguration();
        config.setNotFoundCacheTimeoutSeconds( 1 );

        final ExpiringMemoryNotFoundCache nfc = new ExpiringMemoryNotFoundCache( config );

        final ConcreteResource res = new ConcreteResource( new SimpleLocation( "test:uri" ), "/path/to/expired/object" );

        nfc.addMissing( res );
        assertThat( nfc.isMissing( res ), equalTo( true ) );

        Thread.sleep( TimeUnit.SECONDS.toMillis( 1 ) );

        Set<String> locMissing = nfc.getMissing( res.getLocation() );
        assertThat( locMissing == null || locMissing.isEmpty(), equalTo( true ) );

        Map<Location, Set<String>> allMissing = nfc.getAllMissing();
        assertThat( allMissing == null || allMissing.isEmpty(), equalTo( true ) );

        assertThat( nfc.isMissing( res ), equalTo( false ) );

        locMissing = nfc.getMissing( res.getLocation() );
        assertThat( locMissing == null || locMissing.isEmpty(), equalTo( true ) );

        allMissing = nfc.getAllMissing();
        assertThat( allMissing == null || allMissing.isEmpty(), equalTo( true ) );
    }

}