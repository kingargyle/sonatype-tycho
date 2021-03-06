/*******************************************************************************
 * Copyright (c) 2008, 2011 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *    SAP AG - moved resolution context and download of artifacts out of p2 resolver
 *******************************************************************************/
package org.eclipse.tycho.p2.resolver.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository;
import org.eclipse.equinox.internal.p2.artifact.repository.simple.SimpleArtifactRepository;
import org.eclipse.equinox.internal.p2.core.helpers.OrderedProperties;
import org.eclipse.equinox.internal.p2.director.QueryableArray;
import org.eclipse.equinox.internal.p2.repository.CacheManager;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IArtifactKey;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.VersionedId;
import org.eclipse.equinox.p2.publisher.PublisherInfo;
import org.eclipse.equinox.p2.publisher.PublisherResult;
import org.eclipse.equinox.p2.publisher.actions.JREAction;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRequest;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.repository.spi.AbstractRepository;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.tycho.core.facade.MavenLogger;
import org.eclipse.tycho.p2.impl.Activator;
import org.eclipse.tycho.p2.impl.resolver.ClassifiedLocation;
import org.eclipse.tycho.p2.impl.resolver.DuplicateReactorIUsException;
import org.eclipse.tycho.p2.impl.resolver.LoggingProgressMonitor;
import org.eclipse.tycho.p2.impl.resolver.TychoP2RepositoryCacheManager;
import org.eclipse.tycho.p2.maven.repository.LocalArtifactRepository;
import org.eclipse.tycho.p2.maven.repository.LocalMetadataRepository;
import org.eclipse.tycho.p2.maven.repository.MavenArtifactRepository;
import org.eclipse.tycho.p2.maven.repository.MavenMetadataRepository;
import org.eclipse.tycho.p2.maven.repository.MavenMirrorRequest;
import org.eclipse.tycho.p2.maven.repository.xmlio.MetadataIO;
import org.eclipse.tycho.p2.metadata.IArtifactFacade;
import org.eclipse.tycho.p2.metadata.IReactorArtifactFacade;
import org.eclipse.tycho.p2.repository.GAV;
import org.eclipse.tycho.p2.repository.LocalRepositoryReader;
import org.eclipse.tycho.p2.repository.LocalTychoRepositoryIndex;
import org.eclipse.tycho.p2.repository.RepositoryReader;
import org.eclipse.tycho.p2.repository.TychoRepositoryIndex;
import org.eclipse.tycho.p2.resolver.facade.P2RepositoryCache;
import org.eclipse.tycho.p2.resolver.facade.ResolutionContext;

// This class has been split off from P2Resolver; TODO divide even further
@SuppressWarnings("restriction")
public class ResolutionContextImpl implements ResolutionContext {

    private final MavenLogger logger;

    private final IProgressMonitor monitor;

    private File localMavenRepositoryRoot;

    public ResolutionContextImpl(File localMavenRepositoryRoot, MavenLogger logger) {
        this.logger = logger;
        this.monitor = new LoggingProgressMonitor(logger);

        this.localMavenRepositoryRoot = localMavenRepositoryRoot;
        this.bundlesPublisher = new ResolutionContextBundlePublisher(localMavenRepositoryRoot, logger);
    }

    // ---------------------------------------------------------------------

    private Map<ClassifiedLocation, Set<IInstallableUnit>> reactorProjectIUs = new HashMap<ClassifiedLocation, Set<IInstallableUnit>>();

    private Map<IInstallableUnit, IArtifactFacade> mavenInstallableUnits = new HashMap<IInstallableUnit, IArtifactFacade>();

    private Set<String> reactorInstallableUnitIds = new HashSet<String>();

    public void addReactorArtifact(IReactorArtifactFacade artifact) {
        Set<IInstallableUnit> units = toSet(artifact.getDependencyMetadata(), IInstallableUnit.class);

        ClassifiedLocation key = new ClassifiedLocation(artifact);
        reactorProjectIUs.put(key, units);
        addMavenArtifact(key, artifact, units);

        for (IInstallableUnit unit : units) {
            reactorInstallableUnitIds.add(unit.getId());
        }
    }

    private static <T> Set<T> toSet(Collection<Object> collection, Class<T> targetType) {
        if (collection == null || collection.isEmpty()) {
            return Collections.emptySet();
        }

        LinkedHashSet<T> set = new LinkedHashSet<T>();

        for (Object o : collection) {
            set.add(targetType.cast(o));
        }

        return set;
    }

    public void addArtifactWithExistingMetadata(IArtifactFacade artifact, IArtifactFacade p2MetadataFile) {
        try {
            addMavenArtifact(new ClassifiedLocation(artifact), artifact, readUnits(p2MetadataFile));
        } catch (IOException e) {
            throw new RuntimeException("failed to read p2 metadata", e);
        }
    }

    private Set<IInstallableUnit> readUnits(IArtifactFacade p2MetadataFile) throws IOException {
        FileInputStream inputStream = new FileInputStream(p2MetadataFile.getLocation());
        try {
            MetadataIO io = new MetadataIO();
            return io.readXML(inputStream);
        } finally {
            inputStream.close();
        }
    }

    void addMavenArtifact(ClassifiedLocation key, IArtifactFacade artifact, Set<IInstallableUnit> units) {
        for (IInstallableUnit unit : units) {
            mavenInstallableUnits.put(unit, artifact);
            if (logger.isDebugEnabled()) {
                logger.debug("P2Resolver: artifact "
                        + new GAV(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()).toString()
                        + " resolves installable unit " + new VersionedId(unit.getId(), unit.getVersion()));
            }
        }
    }

    // ----------------------------------------------------------

    private ResolutionContextBundlePublisher bundlesPublisher;

    public void publishAndAddArtifactIfBundleArtifact(IArtifactFacade artifact) {
        IInstallableUnit bundleIU = bundlesPublisher.attemptToPublishBundle(artifact);
        if (bundleIU != null)
            addMavenArtifact(new ClassifiedLocation(artifact), artifact, Collections.singleton(bundleIU));
    }

    /**
     * Returns an {@link IArtifactRepository} instance containing those artifacts in the resolution
     * context which are not in the normal p2 view of the local Maven repository.
     * 
     * @see ResolutionContextImpl#downloadArtifacts(Collection)
     */
    public IArtifactRepository getSupplementaryArtifactRepository() {
        return bundlesPublisher.getArtifactRepoOfPublishedBundles();
    }

    // ------------------------------------------------------------

    /**
     * All known P2 metadata repositories, including maven local repository
     */
    private final List<IMetadataRepository> metadataRepositories = new ArrayList<IMetadataRepository>();

    /**
     * All known P2 artifact repositories, NOT including maven local repository.
     */
    private final List<IArtifactRepository> artifactRepositories = new ArrayList<IArtifactRepository>();

    private P2RepositoryCache repositoryCache;

    private boolean offline;

    private IProvisioningAgent agent;

    public void addP2Repository(URI location) {
        // check metadata cache, first
        IMetadataRepository metadataRepository = (IMetadataRepository) repositoryCache.getMetadataRepository(location);
        IArtifactRepository artifactRepository = (IArtifactRepository) repositoryCache.getArtifactRepository(location);
        if (metadataRepository != null && (offline || artifactRepository != null)) {
            // cache hit
            metadataRepositories.add(metadataRepository);
            if (artifactRepository != null) {
                artifactRepositories.add(artifactRepository);
            }
            logger.info("Adding repository (cached) " + location.toASCIIString());
            return;
        }

        if (agent == null) {
            try {
                agent = Activator.newProvisioningAgent();

                TychoP2RepositoryCacheManager cacheMgr = new TychoP2RepositoryCacheManager();
                cacheMgr.setOffline(offline);
                cacheMgr.setLocalRepositoryLocation(localMavenRepositoryRoot);

                agent.registerService(CacheManager.SERVICE_NAME, cacheMgr);
            } catch (ProvisionException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            IMetadataRepositoryManager metadataRepositoryManager = (IMetadataRepositoryManager) agent
                    .getService(IMetadataRepositoryManager.SERVICE_NAME);
            if (metadataRepositoryManager == null) {
                throw new IllegalStateException("No metadata repository manager found"); //$NON-NLS-1$
            }

            metadataRepository = metadataRepositoryManager.loadRepository(location, monitor);
            metadataRepositories.add(metadataRepository);

            if (!offline || URIUtil.isFileURI(location)) {
                IArtifactRepositoryManager artifactRepositoryManager = (IArtifactRepositoryManager) agent
                        .getService(IArtifactRepositoryManager.SERVICE_NAME);
                if (artifactRepositoryManager == null) {
                    throw new IllegalStateException("No artifact repository manager found"); //$NON-NLS-1$
                }

                artifactRepository = artifactRepositoryManager.loadRepository(location, monitor);
                artifactRepositories.add(artifactRepository);

                forceSingleThreadedDownload(artifactRepositoryManager, artifactRepository);
            }

            repositoryCache.putRepository(location, metadataRepository, artifactRepository);

            // processPartialIUs( metadataRepository, artifactRepository );
        } catch (ProvisionException e) {
            throw new RuntimeException(e);
        }
    }

    protected void forceSingleThreadedDownload(IArtifactRepositoryManager artifactRepositoryManager,
            IArtifactRepository artifactRepository) {
        try {
            if (artifactRepository instanceof SimpleArtifactRepository) {
                forceSingleThreadedDownload((SimpleArtifactRepository) artifactRepository);
            } else if (artifactRepository instanceof CompositeArtifactRepository) {
                forceSingleThreadedDownload(artifactRepositoryManager, (CompositeArtifactRepository) artifactRepository);
            }
        } catch (Exception e) {
            // we've tried
        }
    }

    protected void forceSingleThreadedDownload(IArtifactRepositoryManager artifactRepositoryManager,
            CompositeArtifactRepository artifactRepository) throws ProvisionException {
        List<URI> children = artifactRepository.getChildren();
        for (URI child : children) {
            forceSingleThreadedDownload(artifactRepositoryManager,
                    artifactRepositoryManager.loadRepository(child, monitor));
        }
    }

    protected void forceSingleThreadedDownload(SimpleArtifactRepository artifactRepository) throws SecurityException,
            NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field field = AbstractRepository.class.getDeclaredField("properties");
        field.setAccessible(true);
        OrderedProperties p = (OrderedProperties) field.get(artifactRepository);
        p.put(org.eclipse.equinox.internal.p2.artifact.repository.simple.SimpleArtifactRepository.PROP_MAX_THREADS, "1");
    }

    public void addMavenRepository(URI location, TychoRepositoryIndex projectIndex, RepositoryReader contentLocator) {
        MavenMetadataRepository metadataRepository = (MavenMetadataRepository) repositoryCache
                .getMetadataRepository(location);
        MavenArtifactRepository artifactRepository = (MavenArtifactRepository) repositoryCache
                .getArtifactRepository(location);

        if (metadataRepository == null || artifactRepository == null) {
            metadataRepository = new MavenMetadataRepository(location, projectIndex, contentLocator);
            artifactRepository = new MavenArtifactRepository(location, projectIndex, contentLocator);

            repositoryCache.putRepository(location, metadataRepository, artifactRepository);
        }

        metadataRepositories.add(metadataRepository);
        artifactRepositories.add(artifactRepository);
    }

    public void setRepositoryCache(P2RepositoryCache repositoryCache) {
        this.repositoryCache = repositoryCache;
        initP2ViewsOfLocalMavenRepository();
    }

    // --------------------------------------------------------------------------------
    // creating copy&paste from org.eclipse.equinox.internal.p2.repository.Credentials.forLocation(URI, boolean,
    // AuthenticationInfo)
    public void setCredentials(URI location, String username, String password) {
        ISecurePreferences securePreferences = SecurePreferencesFactory.getDefault();

        // if URI is not opaque, just getting the host may be enough
        String host = location.getHost();
        if (host == null) {
            String scheme = location.getScheme();
            if (URIUtil.isFileURI(location) || scheme == null) {
                // If the URI references a file, a password could possibly be needed for the directory
                // (it could be a protected zip file representing a compressed directory) - in this
                // case the key is the path without the last segment.
                // Using "Path" this way may result in an empty string - which later will result in
                // an invalid key.
                host = new Path(location.toString()).removeLastSegments(1).toString();
            } else {
                // it is an opaque URI - details are unknown - can only use entire string.
                host = location.toString();
            }
        }
        String nodeKey;
        try {
            nodeKey = URLEncoder.encode(host, "UTF-8"); //$NON-NLS-1$
        } catch (UnsupportedEncodingException e2) {
            // fall back to default platform encoding
            try {
                // Uses getProperty "file.encoding" instead of using deprecated URLEncoder.encode(String location)
                // which does the same, but throws NPE on missing property.
                String enc = System.getProperty("file.encoding");//$NON-NLS-1$
                if (enc == null) {
                    throw new UnsupportedEncodingException(
                            "No UTF-8 encoding and missing system property: file.encoding"); //$NON-NLS-1$
                }
                nodeKey = URLEncoder.encode(host, enc);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        String nodeName = IRepository.PREFERENCE_NODE + '/' + nodeKey;

        ISecurePreferences prefNode = securePreferences.node(nodeName);

        try {
            if (!username.equals(prefNode.get(IRepository.PROP_USERNAME, username))
                    || !password.equals(prefNode.get(IRepository.PROP_PASSWORD, password))) {
                logger.info("Redefining access credentials for repository host " + host);
            }
            prefNode.put(IRepository.PROP_USERNAME, username, false);
            prefNode.put(IRepository.PROP_PASSWORD, password, false);
        } catch (StorageException e) {
            throw new RuntimeException(e);
        }
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    public void stop() {
        if (agent != null) {
            agent.stop();
        }
    }

    // -----------------------------------------------------------------

    /** maven local repository as P2 IArtifactRepository */
    private LocalArtifactRepository localRepository;

    /** maven local repository as P2 IMetadataRepository */
    private LocalMetadataRepository localMetadataRepository;

    private void initP2ViewsOfLocalMavenRepository() {
        URI uri = localMavenRepositoryRoot.toURI();

        localRepository = (LocalArtifactRepository) repositoryCache.getArtifactRepository(uri);
        localMetadataRepository = (LocalMetadataRepository) repositoryCache.getMetadataRepository(uri);

        if (localRepository == null || localMetadataRepository == null) {
            RepositoryReader contentLocator = new LocalRepositoryReader(localMavenRepositoryRoot);
            LocalTychoRepositoryIndex artifactsIndex = new LocalTychoRepositoryIndex(localMavenRepositoryRoot,
                    LocalTychoRepositoryIndex.ARTIFACTS_INDEX_RELPATH);
            LocalTychoRepositoryIndex metadataIndex = new LocalTychoRepositoryIndex(localMavenRepositoryRoot,
                    LocalTychoRepositoryIndex.METADATA_INDEX_RELPATH);

            localRepository = new LocalArtifactRepository(localMavenRepositoryRoot, artifactsIndex, contentLocator);
            localMetadataRepository = new LocalMetadataRepository(uri, metadataIndex, contentLocator);

            repositoryCache.putRepository(uri, localMetadataRepository, localRepository);
        }

        // XXX remove old
        metadataRepositories.add(localMetadataRepository);
    }

    // -------------------------------------------------------------------------

    public IQueryable<IInstallableUnit> gatherAvailableInstallableUnits(IProgressMonitor monitor) {
        Set<IInstallableUnit> result = new LinkedHashSet<IInstallableUnit>();

        result.addAll(mavenInstallableUnits.keySet());

        SubMonitor sub = SubMonitor.convert(monitor, metadataRepositories.size() * 200);
        for (IMetadataRepository repository : metadataRepositories) {
            IQueryResult<IInstallableUnit> matches = repository.query(QueryUtil.ALL_UNITS, sub.newChild(100));
            for (Iterator<IInstallableUnit> it = matches.iterator(); it.hasNext();) {
                IInstallableUnit iu = it.next();

                if (isPartialIU(iu)) {
                    logger.debug("PARTIAL IU: " + iu);
                    continue;
                }

                if (!isReactorInstallableUnit(iu)) {
                    if (!reactorInstallableUnitIds.contains(iu.getId())) {
                        result.add(iu);
                    } else {
                        // this produces too much noise in STDOUT
//                        logger.debug( "External IU " + iu + " from repository " + repository.getLocation()
//                            + " has the same id as reactor project. External IU is ignored." );
                    }
                }
            }
        }
        result.addAll(createJREIUs());
        sub.done();
        // this is a real shame
        return new QueryableArray(result.toArray(new IInstallableUnit[result.size()]));
    }

    private static boolean isPartialIU(IInstallableUnit iu) {
        return Boolean.valueOf(iu.getProperty(IInstallableUnit.PROP_PARTIAL_IU)).booleanValue();
    }

    private boolean isReactorInstallableUnit(IInstallableUnit iu) {
        return mavenInstallableUnits.get(iu) instanceof IReactorArtifactFacade;
    }

    /**
     * these dummy IUs are needed to satisfy Import-Package requirements to packages provided by the
     * JDK.
     */
    private Collection<IInstallableUnit> createJREIUs() {
        PublisherResult results = new PublisherResult();
        // TODO use the appropriate profile name
        new JREAction((String) null).perform(new PublisherInfo(), results, new NullProgressMonitor());
        return results.query(QueryUtil.ALL_UNITS, new NullProgressMonitor()).toSet();
    }

    // -------------------------------------------------------------------------------

    private static final IArtifactRequest[] ARTIFACT_REQUEST_ARRAY = new IArtifactRequest[0];

    public void downloadArtifacts(Collection<IInstallableUnit> newState) {
        List<MavenMirrorRequest> requests = new ArrayList<MavenMirrorRequest>();
        for (IInstallableUnit iu : newState) {
            // maven IUs either come from reactor or local maven repository, no need to download them from p2 repos
            if (getMavenArtifact(iu) == null) {
                Collection<IArtifactKey> artifactKeys = iu.getArtifacts();
                for (IArtifactKey key : artifactKeys) {
                    requests.add(new MavenMirrorRequest(key, localRepository));
                }
            }
        }

        for (IArtifactRepository artifactRepository : artifactRepositories) {
            artifactRepository.getArtifacts(requests.toArray(ARTIFACT_REQUEST_ARRAY), monitor);

            requests = filterCompletedRequests(requests);
        }

        localRepository.save();
        localMetadataRepository.save();

        // check for locally installed artifacts, which are not available from any remote repo
        for (Iterator<MavenMirrorRequest> iter = requests.iterator(); iter.hasNext();) {
            MavenMirrorRequest request = iter.next();
            if (localRepository.contains(request.getArtifactKey())) {
                iter.remove();
            }
        }

        if (!requests.isEmpty()) {
            StringBuilder msg = new StringBuilder("Could not download artifacts from any repository\n");
            for (MavenMirrorRequest request : requests) {
                msg.append("   ").append(request.getArtifactKey().toExternalForm()).append('\n');
            }

            throw new RuntimeException(msg.toString());
        }
    }

    private List<MavenMirrorRequest> filterCompletedRequests(List<MavenMirrorRequest> requests) {
        ArrayList<MavenMirrorRequest> filteredRequests = new ArrayList<MavenMirrorRequest>();
        for (MavenMirrorRequest request : requests) {
            if (request.getResult() == null || !request.getResult().isOK()) {
                filteredRequests.add(request);
            }
        }
        return filteredRequests;
    }

    // -------------------------------------------------------------------------------

    public void assertNoDuplicateReactorUIs() throws DuplicateReactorIUsException {
        Map<IInstallableUnit, Set<File>> reactorUIs = new HashMap<IInstallableUnit, Set<File>>();
        Map<IInstallableUnit, Set<File>> duplicateReactorUIs = new HashMap<IInstallableUnit, Set<File>>();

        for (Map.Entry<ClassifiedLocation, Set<IInstallableUnit>> entry : reactorProjectIUs.entrySet()) {
            for (IInstallableUnit iu : entry.getValue()) {
                Set<File> locations = reactorUIs.get(iu);
                if (locations == null) {
                    locations = new LinkedHashSet<File>();
                    reactorUIs.put(iu, locations);
                }
                locations.add(entry.getKey().getLocation());
                if (locations.size() > 1) {
                    duplicateReactorUIs.put(iu, locations);
                }
            }
        }

        if (!duplicateReactorUIs.isEmpty()) {
            throw new DuplicateReactorIUsException(duplicateReactorUIs);
        }
    }

    public LinkedHashSet<IInstallableUnit> getReactorProjectIUs(File projectRoot) {
        LinkedHashSet<IInstallableUnit> ius = new LinkedHashSet<IInstallableUnit>();
        boolean projectExists = false;

        for (Map.Entry<ClassifiedLocation, Set<IInstallableUnit>> entry : reactorProjectIUs.entrySet()) {
            if (projectRoot.equals(entry.getKey().getLocation())) {
                ius.addAll(entry.getValue());
                projectExists = true;
            }
        }

        if (!projectExists)
            throw new IllegalArgumentException("Not a reactor project: " + projectRoot);
        return ius;
    }

    public IArtifactFacade getMavenArtifact(IInstallableUnit iu) {
        return mavenInstallableUnits.get(iu);
    }

    public File getLocalArtifactFile(IArtifactKey key) {
        return localRepository.getArtifactFile(key);
    }
}
