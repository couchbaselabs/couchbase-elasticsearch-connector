/**
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.elasticsearch.transport.couchbase.capi;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MetaDataMappingService;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.PortsRange;
import org.elasticsearch.http.BindHttpException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.transport.couchbase.CouchbaseCAPITransport;

import com.couchbase.capi.CAPIBehavior;
import com.couchbase.capi.CAPIServer;
import com.couchbase.capi.CouchbaseBehavior;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

public class CouchbaseCAPITransportImpl extends AbstractLifecycleComponent<CouchbaseCAPITransport> implements CouchbaseCAPITransport {

    public static final String DEFAULT_DOCUMENT_TYPE_CHECKPOINT = "couchbaseCheckpoint";

    private CAPIBehavior capiBehavior;
    private CouchbaseBehavior couchbaseBehavior;
    private CAPIServer server;
    private Client client;
    private final NetworkService networkService;
    private final IndicesService indicesService;
    private final MetaDataMappingService metaDataMappingService;

    private final String port;
    private final String[] bindHost;
    private final String[] publishHost;

    private final String username;
    private final String password;

    private final Boolean resolveConflicts;
    private final Boolean wrapCounters;
    private final Boolean ignoreFailures;

    private BoundTransportAddress boundAddress;

    private String checkpointDocumentType;
    private String dynamicTypePath;

    private final int numVbuckets;

    private final long maxConcurrentRequests;

    private long bulkIndexRetries;
    private long bulkIndexRetryWaitMs;
    private long bucketUUIDCacheEvictMs;
    private Cache<String, String> bucketUUIDCache;

    private TypeSelector typeSelector;
    private ParentSelector parentSelector;
    private KeyFilter keyFilter;

    private Map<String, String> documentTypeRoutingFields;
    
    private List<String> ignoreDeletes;

    @Inject
    public CouchbaseCAPITransportImpl(Settings settings, RestController restController, NetworkService networkService, IndicesService indicesService, MetaDataMappingService metaDataMappingService, Client client) {
        super(settings);
        
        this.networkService = networkService;
        this.indicesService = indicesService;
        this.metaDataMappingService = metaDataMappingService;
        this.client = client;
        this.port = settings.get("couchbase.port", "9091-10091");
       
        String settingsBindHost = settings.get("bind_host");
        
        if (settingsBindHost == null) {
        	this.bindHost = null;
        }
        else {
        	this.bindHost = new String[1];
        	this.bindHost[0] = settingsBindHost;
        }
        
        String settingsPublishHost = settings.get("publish_host");
        
        if (settingsPublishHost == null) {
        	this.publishHost = null;
        }
        else {
        	this.publishHost = new String[1];
            this.publishHost[0] = settingsPublishHost;
        }
        
        this.username = settings.get("couchbase.username", "Administrator");
        this.password = settings.get("couchbase.password", "");
     
        this.checkpointDocumentType = settings.get("couchbase.typeSelector.checkpointDocumentType", DEFAULT_DOCUMENT_TYPE_CHECKPOINT);
        this.dynamicTypePath = settings.get("couchbase.dynamicTypePath");
        this.resolveConflicts = settings.getAsBoolean("couchbase.resolveConflicts", true);
        this.wrapCounters = settings.getAsBoolean("couchbase.wrapCounters", false);
        this.maxConcurrentRequests = settings.getAsLong("couchbase.maxConcurrentRequests", 1024L);
        this.bulkIndexRetries = settings.getAsLong("couchbase.bulkIndexRetries", 1024L);
        this.bulkIndexRetryWaitMs = settings.getAsLong("couchbase.bulkIndexRetryWaitMs", 1000L);
        this.bucketUUIDCacheEvictMs = settings.getAsLong("couchbase.bucketUUIDCacheEvictMs", 300000L);

        Class<? extends TypeSelector> typeSelectorClass = DefaultTypeSelector.class;
        
        try {
            this.typeSelector = typeSelectorClass.newInstance();
        } catch (Exception e) {
            throw new ElasticsearchException("couchbase.typeSelector", e);
        }
        this.typeSelector.configure(settings);
        logger.info("Couchbase transport is using type selector: {}", typeSelector.getClass().getCanonicalName());

        Class<? extends ParentSelector> parentSelectorClass = DefaultParentSelector.class;
        try {
            this.parentSelector = parentSelectorClass.newInstance();
        } catch (Exception e) {
            throw new ElasticsearchException("couchbase.parentSelector", e);
        }
        this.parentSelector.configure(settings);
        logger.info("Couchbase transport is using parent selector: {}", parentSelector.getClass().getCanonicalName());

        Class<? extends KeyFilter> keyFilterClass = DefaultKeyFilter.class;
        try {
            this.keyFilter = keyFilterClass.newInstance();
        } catch (Exception e) {
            throw new ElasticsearchException("couchbase.keyFilter", e);
        }
        this.keyFilter.configure(settings);
        logger.info("Couchbase transport is using key filter: {}", keyFilter.getClass().getCanonicalName());
        
        int defaultNumVbuckets = 1024;
        if(System.getProperty("os.name").toLowerCase().contains("mac")) {
            logger.info("Detected platform is Mac, changing default num_vbuckets to 64");
            defaultNumVbuckets = 64;
        }

        this.numVbuckets = settings.getAsInt("couchbase.num_vbuckets", defaultNumVbuckets);

        this.bucketUUIDCache = CacheBuilder.newBuilder().expireAfterWrite(this.bucketUUIDCacheEvictMs, TimeUnit.MILLISECONDS).build();

        this.documentTypeRoutingFields = settings.getByPrefix("couchbase.documentTypeRoutingFields.").getAsMap();
        for (String key: documentTypeRoutingFields.keySet()) {
            String routingField = documentTypeRoutingFields.get(key);
            logger.info("Using field {} as routing for type {}", routingField, key);
        }
        
        this.ignoreDeletes = new ArrayList<String>(Arrays.asList(settings.get("couchbase.ignoreDeletes","").split(":")));
        logger.info("Couchbase transport will ignore delete/expiration operations for these buckets: {}", ignoreDeletes);

        this.ignoreFailures = settings.getAsBoolean("couchbase.ignoreFailures", false);
        logger.info("Couchbase transport will ignore indexing failures and not throw exception to Couchbase: {}", ignoreFailures);
    }
    
    private boolean result = false;

    @Override
    protected void doStart() throws ElasticsearchException {
        // Bind and start to accept incoming connections.
        InetAddress[] hostAddressX;
        try {
            hostAddressX = networkService.resolveBindHostAddresses(bindHost);
        } catch (IOException e) {
            throw new BindHttpException("Failed to resolve host [" + bindHost + "]", e);
        }
        final InetAddress[] hostAddress = hostAddressX;
        
        InetAddress publishAddressHostX;
        try {
            publishAddressHostX = networkService.resolvePublishHostAddresses(publishHost);
        } catch (IOException e) {
            throw new BindHttpException("Failed to resolve publish address host [" + publishHost + "]", e);
        }
        final InetAddress publishAddressHost = publishAddressHostX;

        capiBehavior = new ElasticSearchCAPIBehavior(client, logger, keyFilter, typeSelector, parentSelector, checkpointDocumentType, dynamicTypePath, resolveConflicts, wrapCounters, maxConcurrentRequests, bulkIndexRetries, bulkIndexRetryWaitMs, bucketUUIDCache, documentTypeRoutingFields, ignoreDeletes, ignoreFailures);
        couchbaseBehavior = new ElasticSearchCouchbaseBehavior(client, logger, checkpointDocumentType, bucketUUIDCache);

        PortsRange portsRange = new PortsRange(port);
        final AtomicReference<Exception> lastException = new AtomicReference<Exception>();
     
        boolean success = portsRange.iterate(new PortsRange.PortCallback() {
            @Override
            public boolean onPortNumber(int portNumber) {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    	public Void run() {
                            try {
			                    server = new CAPIServer(capiBehavior, couchbaseBehavior,
			                            new InetSocketAddress(hostAddress[2], portNumber),
			                            CouchbaseCAPITransportImpl.this.username,
			                            CouchbaseCAPITransportImpl.this.password,
			                            numVbuckets);
	                  
			                    if (publishAddressHost != null) {
			                    	server.setPublishAddress(publishAddressHost);
			                    }                    	
	
			                    server.start();
			                    result = true;
                            } catch (Exception e) {
                                lastException.set(e);
                                result = false;
                            }
                            	
                            return null;
                    	}
                    });
                    return result;
                }
        });
        if (!success) {
            throw new BindHttpException("Failed to bind to [" + port + "]",
                    lastException.get());
        }

        InetSocketAddress boundAddress = server.getBindAddress();
        InetSocketAddress publishAddress = new InetSocketAddress(publishAddressHost, boundAddress.getPort());
        
        logger.info("Host: {}, Port {}", publishAddressHost.getHostAddress(), boundAddress.getPort());
        
        InetSocketTransportAddress[] array = new InetSocketTransportAddress[1];
        array[0] = new InetSocketTransportAddress(boundAddress);
        
        this.boundAddress = new BoundTransportAddress(array, new InetSocketTransportAddress(publishAddress));
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        if(server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                throw new ElasticsearchException("Error stopping jetty", e);
            }
        }
    }

    @Override
    protected void doClose() throws ElasticsearchException {

    }

    @Override
    public BoundTransportAddress boundAddress() {
        return boundAddress;
    }

}
