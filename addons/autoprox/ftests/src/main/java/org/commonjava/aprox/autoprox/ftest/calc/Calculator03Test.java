package org.commonjava.aprox.autoprox.ftest.calc;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.commonjava.aprox.autoprox.rest.dto.AutoProxCalculation;
import org.commonjava.aprox.model.core.ArtifactStore;
import org.commonjava.aprox.model.core.Group;
import org.commonjava.aprox.model.core.HostedRepository;
import org.commonjava.aprox.model.core.RemoteRepository;
import org.commonjava.aprox.model.core.StoreType;
import org.junit.Test;

public class Calculator03Test
    extends AbstractAutoproxCalculatorTest
{

    @Test
    public void calculatedRemoteUsesUrlFromTestScript()
        throws Exception
    {
        final String name = "test";
        final AutoProxCalculation calculation = module.calculateRuleOutput( StoreType.group, name );

        assertThat( calculation.getRuleName(), equalTo( "0001-simple-rule.groovy" ) );

        final List<ArtifactStore> supplemental = calculation.getSupplementalStores();
        assertThat( supplemental.size(), equalTo( 4 ) );

        final Group store = (Group) calculation.getStore();
        assertThat( store.getName(), equalTo( name ) );

        int idx = 0;
        ArtifactStore supp = supplemental.get( idx );
        assertThat( supp.getName(), equalTo( name ) );
        assertThat( supp instanceof HostedRepository, equalTo( true ) );

        final HostedRepository hosted = (HostedRepository) supp;
        assertThat( hosted.isAllowReleases(), equalTo( true ) );
        assertThat( hosted.isAllowSnapshots(), equalTo( true ) );

        idx++;
        supp = supplemental.get( idx );
        assertThat( supp.getName(), equalTo( name ) );
        assertThat( supp instanceof RemoteRepository, equalTo( true ) );

        final RemoteRepository remote = (RemoteRepository) supp;
        assertThat( remote.getUrl(), equalTo( "http://localhost:1000/target/" + name ) );

    }

}
