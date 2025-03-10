// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.lake;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.staros.client.StarClient;
import com.staros.client.StarClientException;
import com.staros.manager.StarManagerServer;
import com.staros.proto.CreateShardGroupInfo;
import com.staros.proto.CreateShardInfo;
import com.staros.proto.FileCacheInfo;
import com.staros.proto.FilePathInfo;
import com.staros.proto.FileStoreType;
import com.staros.proto.PlacementPolicy;
import com.staros.proto.ReplicaInfo;
import com.staros.proto.ReplicaRole;
import com.staros.proto.ServiceInfo;
import com.staros.proto.ShardGroupInfo;
import com.staros.proto.ShardInfo;
import com.staros.proto.StatusCode;
import com.staros.proto.WorkerInfo;
import com.staros.util.LockCloseable;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.UserException;
import com.starrocks.server.GlobalStateMgr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * StarOSAgent is responsible for
 * 1. Encapsulation of StarClient api.
 * 2. Maintenance of StarOS worker to StarRocks backend map.
 */
public class StarOSAgent {
    private static final Logger LOG = LogManager.getLogger(StarOSAgent.class);

    public static final String SERVICE_NAME = "starrocks";

    private StarClient client;
    private String serviceId;
    private Map<String, Long> workerToId;
    private Map<Long, Long> workerToBackend;
    private ReentrantReadWriteLock rwLock;

    public StarOSAgent() {
        serviceId = "";
        workerToId = Maps.newHashMap();
        workerToBackend = Maps.newHashMap();
        rwLock = new ReentrantReadWriteLock();
    }

    public boolean init(StarManagerServer server) {
        if (Config.integrate_starmgr) {
            if (!Config.use_staros) {
                LOG.error("integrate_starmgr is true but use_staros is false!");
                return false;
            }
            // check if Config.starmanager_address == FE address
            String[] starMgrAddr = Config.starmgr_address.split(":");
            if (!starMgrAddr[0].equals("127.0.0.1")) {
                LOG.error("Config.starmgr_address not equal 127.0.0.1, it is {}", starMgrAddr[0]);
                return false;
            }
        }

        client = new StarClient(server);
        client.connectServer(Config.starmgr_address);
        return true;
    }

    private void prepare() {
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            if (serviceId.equals("")) {
                getServiceId();
            }
        }
    }

    public void getServiceId() {
        try {
            ServiceInfo serviceInfo = client.getServiceInfoByName(SERVICE_NAME);
            serviceId = serviceInfo.getServiceId();
        } catch (StarClientException e) {
            LOG.warn("Failed to get serviceId from starMgr. Error: {}", e);
            return;
        }
        LOG.info("get serviceId {} from starMgr", serviceId);
    }

    public FilePathInfo allocateFilePath(long tableId) throws DdlException {
        try {
            EnumDescriptor enumDescriptor = FileStoreType.getDescriptor();
            FileStoreType fsType = FileStoreType.valueOf(enumDescriptor.findValueByName(Config.default_fs_type).getNumber());
            FilePathInfo pathInfo = client.allocateFilePath(serviceId, fsType, Long.toString(tableId));
            LOG.debug("Allocate file path from starmgr: {}", pathInfo);
            return pathInfo;
        } catch (StarClientException e) {
            throw new DdlException("Failed to allocate file path from StarMgr", e);
        }
    }


    public boolean registerAndBootstrapService() {
        try {
            client.registerService("starrocks");
        } catch (StarClientException e) {
            if (e.getCode() != StatusCode.ALREADY_EXIST) {
                LOG.error("Failed to register service from starMgr. Error: {}", e);
                return false;
            }
        }

        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            try {
                serviceId = client.bootstrapService("starrocks", SERVICE_NAME);
                LOG.info("get serviceId: {} by bootstrapService to starMgr", serviceId);
            } catch (StarClientException e) {
                if (e.getCode() != StatusCode.ALREADY_EXIST) {
                    LOG.error("Failed to bootstrap service from starMgr. Error: {}", e);
                    return false;
                } else {
                    getServiceId();
                }
            }
        }
        return true;
    }

    // for ut only
    public long getWorkerId(String workerIpPort) {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            return workerToId.get(workerIpPort);
        }
    }

    private long getWorker(String workerIpPort) throws DdlException {
        long workerId = -1;
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            if (workerToId.containsKey(workerIpPort)) {
                workerId = workerToId.get(workerIpPort);

            } else {
                // When FE && staros restart, workerToId is Empty, but staros already persisted
                // worker infos, so we need to get workerId from starMgr
                try {
                    WorkerInfo workerInfo = client.getWorkerInfo(serviceId, workerIpPort);
                    workerId = workerInfo.getWorkerId();
                } catch (StarClientException e) {
                    if (e.getCode() != StatusCode.NOT_EXIST) {
                        throw new DdlException("Failed to get worker id from starMgr. error: "
                                + e.getMessage());
                    }

                    LOG.info("worker {} not exist.", workerIpPort);
                }
            }
        }

        return workerId;
    }

    public void addWorker(long backendId, String workerIpPort) {
        prepare();
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            if (serviceId.equals("")) {
                LOG.warn("When addWorker serviceId is empty");
                return;
            }

            if (workerToId.containsKey(workerIpPort)) {
                return;
            }

            long workerId = -1;
            try {
                workerId = client.addWorker(serviceId, workerIpPort);
            } catch (StarClientException e) {
                if (e.getCode() != StatusCode.ALREADY_EXIST) {
                    LOG.warn("Failed to addWorker. Error: {}", e);
                    return;
                } else {
                    // get workerId from starMgr
                    try {
                        WorkerInfo workerInfo = client.getWorkerInfo(serviceId, workerIpPort);
                        workerId = workerInfo.getWorkerId();
                    } catch (StarClientException e2) {
                        LOG.warn("Failed to get getWorkerInfo. Error: {}", e2);
                        return;
                    }
                    LOG.info("worker {} already added in starMgr", workerId);
                }
            }
            workerToId.put(workerIpPort, workerId);
            workerToBackend.put(workerId, backendId);
            LOG.info("add worker {} success, backendId is {}", workerId, backendId);
        }
    }

    public void removeWorker(String workerIpPort) throws DdlException {
        prepare();

        long workerId = getWorker(workerIpPort);

        try {
            client.removeWorker(serviceId, workerId);
        } catch (StarClientException e) {
            // when multi threads remove this worker, maybe we would get "NOT_EXIST"
            // but it is right, so only need to throw exception
            // if code is not StarClientException.ExceptionCode.NOT_EXIST
            if (e.getCode() != StatusCode.NOT_EXIST) {
                throw new DdlException("Failed to remove worker. error: " + e.getMessage());
            }
        }

        removeWorkerFromMap(workerId, workerIpPort);
    }

    public void removeWorkerFromMap(long workerId, String workerIpPort) {
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            workerToBackend.remove(workerId);
            workerToId.remove(workerIpPort);
        }

        LOG.info("remove worker {} success from StarMgr", workerIpPort);
    }

    public long getWorkerIdByBackendId(long backendId) {
        try (LockCloseable lock = new LockCloseable(rwLock.readLock())) {
            long workerId = -1;
            for (Map.Entry<Long, Long> entry : workerToBackend.entrySet()) {
                if (entry.getValue() == backendId) {
                    workerId = entry.getKey();
                    break;
                }
            }
            return workerId;
        }
    }

    public long createShardGroup(long dbId, long tableId, long partitionId) throws DdlException {
        prepare();
        List<ShardGroupInfo> shardGroupInfos = null;
        try {
            List<CreateShardGroupInfo> createShardGroupInfos = new ArrayList<>();
            createShardGroupInfos.add(CreateShardGroupInfo.newBuilder()
                    .setPolicy(PlacementPolicy.SPREAD)
                    .putLabels("dbId", String.valueOf(dbId))
                    .putLabels("tableId", String.valueOf(tableId))
                    .putLabels("partitionId", String.valueOf(partitionId))
                    .putProperties("createTime", String.valueOf(System.currentTimeMillis()))
                    .build());
            shardGroupInfos = client.createShardGroup(serviceId, createShardGroupInfos);
            LOG.debug("Create shard group success. shard group infos: {}", shardGroupInfos);
            Preconditions.checkState(shardGroupInfos.size() == 1);
        } catch (StarClientException e) {
            throw new DdlException("Failed to create shard group. error: " + e.getMessage());
        }
        return shardGroupInfos.stream().map(ShardGroupInfo::getGroupId).collect(Collectors.toList()).get(0);
    }

    public void deleteShardGroup(List<Long> groupIds) {
        prepare();
        try {
            client.deleteShardGroup(serviceId, groupIds, true);
        } catch (StarClientException e) {
            LOG.warn("Failed to delete shard group. error: {}", e.getMessage());
        }
    }

    public List<ShardGroupInfo> listShardGroup() {
        prepare();
        try {
            return client.listShardGroup(serviceId);
        } catch (StarClientException e) {
            LOG.info("list shard group failed. Error: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Long> createShards(int numShards, int replicaNum, FilePathInfo pathInfo, FileCacheInfo cacheInfo, long groupId)
        throws DdlException {
        prepare();
        List<ShardInfo> shardInfos = null;
        try {
            List<CreateShardInfo> createShardInfoList = new ArrayList<>(numShards);

            CreateShardInfo.Builder builder = CreateShardInfo.newBuilder();
            builder.setReplicaCount(replicaNum)
                    .addGroupIds(groupId)
                    .setPathInfo(pathInfo)
                    .setCacheInfo(cacheInfo);

            for (int i = 0; i < numShards; ++i) {
                builder.setShardId(GlobalStateMgr.getCurrentState().getNextId());
                createShardInfoList.add(builder.build());
            }
            shardInfos = client.createShard(serviceId, createShardInfoList);
            LOG.debug("Create shards success. shard infos: {}", shardInfos);
        } catch (Exception e) {
            throw new DdlException("Failed to create shards. error: " + e.getMessage());
        }

        Preconditions.checkState(shardInfos.size() == numShards);
        return shardInfos.stream().map(ShardInfo::getShardId).collect(Collectors.toList());
    }

    public List<Long> listShard(long groupId) throws DdlException {
        prepare();

        List<List<ShardInfo>> shardInfo;
        try {
            shardInfo = client.listShard(serviceId, Arrays.asList(groupId));
        } catch (StarClientException e) {
            throw new DdlException("Failed to list shards. error: " + e.getMessage());
        }
        return shardInfo.get(0).stream().map(ShardInfo::getShardId).collect(Collectors.toList());
    }

    public void deleteShards(Set<Long> shardIds) throws DdlException {
        prepare();
        try {
            client.deleteShard(serviceId, shardIds);
        } catch (StarClientException e) {
            LOG.warn("Failed to delete shards. error: {}", e.getMessage());
            throw new DdlException("Failed to delete shards. error: " + e.getMessage());
        }
    }

    private List<ReplicaInfo> getShardReplicas(long shardId) throws UserException {
        prepare();
        try {
            List<ShardInfo> shardInfos = client.getShardInfo(serviceId, Lists.newArrayList(shardId));
            Preconditions.checkState(shardInfos.size() == 1);
            return shardInfos.get(0).getReplicaInfoList();
        } catch (StarClientException e) {
            throw new UserException("Failed to get shard info. error: " + e.getMessage());
        }
    }

    public long getPrimaryBackendIdByShard(long shardId) throws UserException {
        List<ReplicaInfo> replicas = getShardReplicas(shardId);

        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            for (ReplicaInfo replicaInfo : replicas) {
                if (replicaInfo.getReplicaRole() == ReplicaRole.PRIMARY) {
                    WorkerInfo workerInfo = replicaInfo.getWorkerInfo();
                    long workerId = workerInfo.getWorkerId();
                    if (!workerToBackend.containsKey(workerId)) {
                        // get backendId from system info by host & starletPort
                        String workerAddr = workerInfo.getIpPort();
                        String[] pair = workerAddr.split(":");
                        long backendId = GlobalStateMgr.getCurrentSystemInfo()
                                .getBackendIdWithStarletPort(pair[0], Integer.parseInt(pair[1]));

                        if (backendId == -1L) {
                            throw new UserException("Failed to get backend by worker. worker id: " + workerId);
                        }

                        // put it into map
                        workerToId.put(workerAddr, workerId);
                        workerToBackend.put(workerId, backendId);
                        return backendId;
                    }

                    return workerToBackend.get(workerId);
                }
            }
        }
        throw new UserException("Failed to get primary backend. shard id: " + shardId);
    }

    public Set<Long> getBackendIdsByShard(long shardId) throws UserException {
        List<ReplicaInfo> replicas = getShardReplicas(shardId);

        Set<Long> backendIds = Sets.newHashSet();
        try (LockCloseable lock = new LockCloseable(rwLock.writeLock())) {
            for (ReplicaInfo replicaInfo : replicas) {
                // TODO: check worker state
                WorkerInfo workerInfo = replicaInfo.getWorkerInfo();
                long workerId = workerInfo.getWorkerId();
                if (!workerToBackend.containsKey(workerId)) {
                    // get backendId from system info
                    String workerAddr = workerInfo.getIpPort();
                    String[] pair = workerAddr.split(":");
                    long backendId = GlobalStateMgr.getCurrentSystemInfo()
                            .getBackendIdWithStarletPort(pair[0], Integer.parseInt(pair[1]));

                    if (backendId == -1L) {
                        LOG.warn("backendId for {} is -1", workerAddr);
                        continue;
                    }

                    // put it into map
                    workerToId.put(workerAddr, workerId);
                    workerToBackend.put(workerId, backendId);
                    backendIds.add(backendId);
                } else {
                    backendIds.add(workerToBackend.get(workerId));
                }
            }
        }
        return backendIds;
    }
}
