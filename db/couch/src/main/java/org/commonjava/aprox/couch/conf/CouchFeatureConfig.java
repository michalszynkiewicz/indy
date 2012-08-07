package org.commonjava.aprox.couch.conf;

import javax.enterprise.inject.Default;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.commonjava.aprox.core.conf.AproxConfigInfo;
import org.commonjava.aprox.core.conf.AproxFeatureConfig;
import org.commonjava.couch.conf.CouchDBConfiguration;
import org.commonjava.couch.conf.DefaultCouchDBConfiguration;
import org.commonjava.web.config.ConfigurationException;

@Singleton
public final class CouchFeatureConfig
    extends AproxFeatureConfig<CouchDBConfiguration, DefaultCouchDBConfiguration>
{

    @Inject
    private CouchConfigInfo info;

    public CouchFeatureConfig()
    {
        super( DefaultCouchDBConfiguration.class );
    }

    @Produces
    @Default
    public CouchDBConfiguration getCouchConfig()
        throws ConfigurationException
    {
        return getConfig();
    }

    @Override
    public AproxConfigInfo getInfo()
    {
        return info;
    }

    @Singleton
    public static final class CouchConfigInfo
        extends AproxConfigInfo
    {
        public CouchConfigInfo()
        {
            super( DefaultCouchDBConfiguration.class, "db-couch" );
        }
    }

}