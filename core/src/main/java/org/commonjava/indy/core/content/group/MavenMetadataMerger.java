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
package org.commonjava.indy.core.content.group;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.commonjava.indy.util.LocationUtils.getKey;

import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.maven.galley.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@javax.enterprise.context.ApplicationScoped
public class MavenMetadataMerger
    implements MetadataMerger
{

    public class SnapshotVersionComparator
        implements Comparator<SnapshotVersion>
    {
        @Override
        public int compare( final SnapshotVersion first, final SnapshotVersion second )
        {
            int comp = first.getVersion()
                            .compareTo( second.getVersion() );
            if ( comp == 0 )
            {
                comp = first.getExtension()
                            .compareTo( second.getExtension() );
            }

            return comp;
        }
    }

    public static final String METADATA_NAME = "maven-metadata.xml";

    public static final String METADATA_SHA_NAME = METADATA_NAME + ".sha1";

    public static final String METADATA_SHA256_NAME = METADATA_NAME + ".sha256";

    public static final String METADATA_MD5_NAME = METADATA_NAME + ".md5";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Override
    public byte[] merge( final Collection<Transfer> sources, final Group group, final String path )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Generating merged metadata in: {}:{}", group.getKey(), path );

        final Metadata master = new Metadata();
        final MetadataXpp3Reader reader = new MetadataXpp3Reader();
        final FileReader fr = null;
        InputStream stream = null;

        boolean merged = false;
        Transfer snapshotProvider = null;
        for ( final Transfer src : sources )
        {
            try
            {
                stream = src.openInputStream();
                String content = IOUtils.toString( stream );
                logger.debug( "Adding in metadata content from: {}\n\n{}\n\n", src, content );

                // there is a lot of junk in here to make up for Metadata's anemic merge() method.
                final Metadata md = reader.read( new StringReader( content ), false );

                if ( md.getGroupId() != null )
                {
                    master.setGroupId( md.getGroupId() );
                }

                if ( md.getArtifactId() != null )
                {
                    master.setArtifactId( md.getArtifactId() );
                }

                if ( md.getVersion() != null )
                {
                    master.setVersion( md.getVersion() );
                }

                master.merge( md );

                Versioning versioning = master.getVersioning();
                if ( versioning == null )
                {
                    master.setVersioning( new Versioning() );
                }
                else
                {
                    Versioning mdVersioning = md.getVersioning();

                    // FIXME: Should we try to merge snapshot lists instead of using the first one we encounter??
                    if ( versioning.getSnapshot() == null )
                    {
                        logger.info( "INCLUDING snapshot information from: {} in: {}:{}", src, group.getKey(), path );
                        snapshotProvider = src;

                        versioning.setSnapshot( mdVersioning.getSnapshot() );

                        final List<SnapshotVersion> snapshotVersions = versioning
                                .getSnapshotVersions();
                        boolean added = false;
                        for ( final SnapshotVersion snap : mdVersioning
                                .getSnapshotVersions() )
                        {
                            if ( !snapshotVersions.contains( snap ) )
                            {
                                snapshotVersions.add( snap );
                                added = true;
                            }
                        }

                        if ( added )
                        {
                            Collections.sort( snapshotVersions, new SnapshotVersionComparator() );
                        }
                    }
                    else
                    {
                        logger.warn( "SKIPPING snapshot information from: {} in: {}:{} (obscured by: {})", src, group.getKey(), path, snapshotProvider );
                    }
                }

                merged = true;
            }
            catch ( final IOException e )
            {
                final StoreKey key = getKey( src );
                logger.error( String.format( "Cannot read metadata: %s from artifact-store: %s. Reason: %s", src.getPath(), key, e.getMessage() ), e );
            }
            catch ( final XmlPullParserException e )
            {
                final StoreKey key = getKey( src );
                logger.error( String.format( "Cannot parse metadata: %s from artifact-store: %s. Reason: %s", src.getPath(), key, e.getMessage() ), e );
            }
            finally
            {
                closeQuietly( fr );
                closeQuietly( stream );
            }
        }

        if ( merged )
        {
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try
            {
                new MetadataXpp3Writer().write( baos, master );

                return baos.toByteArray();
            }
            catch ( final IOException e )
            {
                logger.error( String.format( "Cannot write consolidated metadata: %s to: %s. Reason: %s", path, group.getKey(), e.getMessage() ), e );
            }
        }

        return null;
    }

}
