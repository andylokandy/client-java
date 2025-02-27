/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tikv.common;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.tikv.common.operation.PDErrorHandler.getRegionResponseErrorExtractor;
import static org.tikv.common.pd.PDError.buildFromPdpbError;
import static org.tikv.common.pd.PDUtils.addrToUri;
import static org.tikv.common.pd.PDUtils.uriToAddr;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.protobuf.ByteString;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.grpc.ManagedChannel;
import io.prometheus.client.Histogram;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.TiConfiguration.KVMode;
import org.tikv.common.codec.Codec.BytesCodec;
import org.tikv.common.codec.CodecDataOutput;
import org.tikv.common.codec.KeyUtils;
import org.tikv.common.exception.GrpcException;
import org.tikv.common.exception.TiClientInternalException;
import org.tikv.common.meta.TiTimestamp;
import org.tikv.common.operation.NoopHandler;
import org.tikv.common.operation.PDErrorHandler;
import org.tikv.common.region.TiRegion;
import org.tikv.common.util.BackOffFunction.BackOffFuncType;
import org.tikv.common.util.BackOffer;
import org.tikv.common.util.ChannelFactory;
import org.tikv.common.util.ConcreteBackOffer;
import org.tikv.common.util.FutureObserver;
import org.tikv.kvproto.Metapb.Store;
import org.tikv.kvproto.PDGrpc;
import org.tikv.kvproto.PDGrpc.PDBlockingStub;
import org.tikv.kvproto.PDGrpc.PDStub;
import org.tikv.kvproto.Pdpb.Error;
import org.tikv.kvproto.Pdpb.ErrorType;
import org.tikv.kvproto.Pdpb.GetAllStoresRequest;
import org.tikv.kvproto.Pdpb.GetMembersRequest;
import org.tikv.kvproto.Pdpb.GetMembersResponse;
import org.tikv.kvproto.Pdpb.GetOperatorRequest;
import org.tikv.kvproto.Pdpb.GetOperatorResponse;
import org.tikv.kvproto.Pdpb.GetRegionByIDRequest;
import org.tikv.kvproto.Pdpb.GetRegionRequest;
import org.tikv.kvproto.Pdpb.GetRegionResponse;
import org.tikv.kvproto.Pdpb.GetStoreRequest;
import org.tikv.kvproto.Pdpb.GetStoreResponse;
import org.tikv.kvproto.Pdpb.OperatorStatus;
import org.tikv.kvproto.Pdpb.RequestHeader;
import org.tikv.kvproto.Pdpb.ResponseHeader;
import org.tikv.kvproto.Pdpb.ScatterRegionRequest;
import org.tikv.kvproto.Pdpb.ScatterRegionResponse;
import org.tikv.kvproto.Pdpb.Timestamp;
import org.tikv.kvproto.Pdpb.TsoRequest;
import org.tikv.kvproto.Pdpb.TsoResponse;

public class PDClient extends AbstractGRPCClient<PDBlockingStub, PDStub>
    implements ReadOnlyPDClient {
  private static final String TIFLASH_TABLE_SYNC_PROGRESS_PATH = "/tiflash/table/sync";
  private final Logger logger = LoggerFactory.getLogger(PDClient.class);
  private RequestHeader header;
  private TsoRequest tsoReq;
  private volatile LeaderWrapper leaderWrapper;
  private ScheduledExecutorService service;
  private ScheduledExecutorService tiflashReplicaService;
  private List<URI> pdAddrs;
  private Client etcdClient;
  private ConcurrentMap<Long, Double> tiflashReplicaMap;
  private HostMapping hostMapping;

  public static final Histogram PD_GET_REGION_BY_KEY_REQUEST_LATENCY =
      Histogram.build()
          .name("client_java_pd_get_region_by_requests_latency")
          .help("pd getRegionByKey request latency.")
          .register();

  private PDClient(TiConfiguration conf, ChannelFactory channelFactory) {
    super(conf, channelFactory);
    initCluster();
    this.blockingStub = getBlockingStub();
    this.asyncStub = getAsyncStub();
  }

  public static ReadOnlyPDClient create(TiConfiguration conf, ChannelFactory channelFactory) {
    return createRaw(conf, channelFactory);
  }

  static PDClient createRaw(TiConfiguration conf, ChannelFactory channelFactory) {
    return new PDClient(conf, channelFactory);
  }

  public HostMapping getHostMapping() {
    return hostMapping;
  }

  @Override
  public TiTimestamp getTimestamp(BackOffer backOffer) {
    Supplier<TsoRequest> request = () -> tsoReq;

    PDErrorHandler<TsoResponse> handler =
        new PDErrorHandler<>(
            r -> r.getHeader().hasError() ? buildFromPdpbError(r.getHeader().getError()) : null,
            this);

    TsoResponse resp = callWithRetry(backOffer, PDGrpc.getTsoMethod(), request, handler);
    Timestamp timestamp = resp.getTimestamp();
    return new TiTimestamp(timestamp.getPhysical(), timestamp.getLogical());
  }

  /**
   * Sends request to pd to scatter region.
   *
   * @param region represents a region info
   */
  void scatterRegion(TiRegion region, BackOffer backOffer) {
    Supplier<ScatterRegionRequest> request =
        () ->
            ScatterRegionRequest.newBuilder().setHeader(header).setRegionId(region.getId()).build();

    PDErrorHandler<ScatterRegionResponse> handler =
        new PDErrorHandler<>(
            r -> r.getHeader().hasError() ? buildFromPdpbError(r.getHeader().getError()) : null,
            this);

    ScatterRegionResponse resp =
        callWithRetry(backOffer, PDGrpc.getScatterRegionMethod(), request, handler);
    // TODO: maybe we should retry here, need dig into pd's codebase.
    if (resp.hasHeader() && resp.getHeader().hasError()) {
      throw new TiClientInternalException(
          String.format("failed to scatter region because %s", resp.getHeader().getError()));
    }
  }

  /**
   * wait scatter region until finish
   *
   * @param region
   */
  void waitScatterRegionFinish(TiRegion region, BackOffer backOffer) {
    for (; ; ) {
      GetOperatorResponse resp = getOperator(region.getId());
      if (resp != null) {
        if (isScatterRegionFinish(resp)) {
          logger.info(String.format("wait scatter region on %d is finished", region.getId()));
          return;
        } else {
          backOffer.doBackOff(
              BackOffFuncType.BoRegionMiss, new GrpcException("waiting scatter region"));
          logger.info(
              String.format(
                  "wait scatter region %d at key %s is %s",
                  region.getId(),
                  KeyUtils.formatBytes(resp.getDesc().toByteArray()),
                  resp.getStatus().toString()));
        }
      }
    }
  }

  private GetOperatorResponse getOperator(long regionId) {
    Supplier<GetOperatorRequest> request =
        () -> GetOperatorRequest.newBuilder().setHeader(header).setRegionId(regionId).build();
    // get operator no need to handle error and no need back offer.
    return callWithRetry(
        ConcreteBackOffer.newCustomBackOff(0),
        PDGrpc.getGetOperatorMethod(),
        request,
        new NoopHandler<>());
  }

  private boolean isScatterRegionFinish(GetOperatorResponse resp) {
    // If the current operator of region is not `scatter-region`, we could assume
    // that `scatter-operator` has finished or timeout.
    boolean finished =
        !resp.getDesc().equals(ByteString.copyFromUtf8("scatter-region"))
            || resp.getStatus() != OperatorStatus.RUNNING;

    if (resp.hasHeader()) {
      ResponseHeader header = resp.getHeader();
      if (header.hasError()) {
        Error error = header.getError();
        // heartbeat may not send to PD
        if (error.getType() == ErrorType.REGION_NOT_FOUND) {
          finished = true;
        }
      }
    }
    return finished;
  }

  @Override
  public TiRegion getRegionByKey(BackOffer backOffer, ByteString key) {
    Histogram.Timer requestTimer = PD_GET_REGION_BY_KEY_REQUEST_LATENCY.startTimer();
    try {
      if (conf.getKvMode() == KVMode.TXN) {
        CodecDataOutput cdo = new CodecDataOutput();
        BytesCodec.writeBytes(cdo, key.toByteArray());
        key = cdo.toByteString();
      }
      ByteString queryKey = key;

      Supplier<GetRegionRequest> request =
          () -> GetRegionRequest.newBuilder().setHeader(header).setRegionKey(queryKey).build();

      PDErrorHandler<GetRegionResponse> handler =
          new PDErrorHandler<>(getRegionResponseErrorExtractor, this);

      GetRegionResponse resp =
          callWithRetry(backOffer, PDGrpc.getGetRegionMethod(), request, handler);
      return new TiRegion(
          resp.getRegion(),
          resp.getLeader(),
          conf.getIsolationLevel(),
          conf.getCommandPriority(),
          conf.getKvMode(),
          conf.getReplicaSelector());
    } finally {
      requestTimer.observeDuration();
    }
  }

  @Override
  public Future<TiRegion> getRegionByKeyAsync(BackOffer backOffer, ByteString key) {
    FutureObserver<TiRegion, GetRegionResponse> responseObserver =
        new FutureObserver<>(
            resp ->
                new TiRegion(
                    resp.getRegion(),
                    resp.getLeader(),
                    conf.getIsolationLevel(),
                    conf.getCommandPriority(),
                    conf.getKvMode(),
                    conf.getReplicaSelector()));
    Supplier<GetRegionRequest> request =
        () -> GetRegionRequest.newBuilder().setHeader(header).setRegionKey(key).build();

    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(getRegionResponseErrorExtractor, this);

    callAsyncWithRetry(backOffer, PDGrpc.getGetRegionMethod(), request, responseObserver, handler);
    return responseObserver.getFuture();
  }

  @Override
  public TiRegion getRegionByID(BackOffer backOffer, long id) {
    Supplier<GetRegionByIDRequest> request =
        () -> GetRegionByIDRequest.newBuilder().setHeader(header).setRegionId(id).build();
    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(getRegionResponseErrorExtractor, this);

    GetRegionResponse resp =
        callWithRetry(backOffer, PDGrpc.getGetRegionByIDMethod(), request, handler);
    // Instead of using default leader instance, explicitly set no leader to null
    return new TiRegion(
        resp.getRegion(),
        resp.getLeader(),
        conf.getIsolationLevel(),
        conf.getCommandPriority(),
        conf.getKvMode(),
        conf.getReplicaSelector());
  }

  @Override
  public Future<TiRegion> getRegionByIDAsync(BackOffer backOffer, long id) {
    FutureObserver<TiRegion, GetRegionResponse> responseObserver =
        new FutureObserver<>(
            resp ->
                new TiRegion(
                    resp.getRegion(),
                    resp.getLeader(),
                    conf.getIsolationLevel(),
                    conf.getCommandPriority(),
                    conf.getKvMode(),
                    conf.getReplicaSelector()));

    Supplier<GetRegionByIDRequest> request =
        () -> GetRegionByIDRequest.newBuilder().setHeader(header).setRegionId(id).build();
    PDErrorHandler<GetRegionResponse> handler =
        new PDErrorHandler<>(getRegionResponseErrorExtractor, this);

    callAsyncWithRetry(
        backOffer, PDGrpc.getGetRegionByIDMethod(), request, responseObserver, handler);
    return responseObserver.getFuture();
  }

  private Supplier<GetStoreRequest> buildGetStoreReq(long storeId) {
    return () -> GetStoreRequest.newBuilder().setHeader(header).setStoreId(storeId).build();
  }

  private Supplier<GetAllStoresRequest> buildGetAllStoresReq() {
    return () -> GetAllStoresRequest.newBuilder().setHeader(header).build();
  }

  private <T> PDErrorHandler<GetStoreResponse> buildPDErrorHandler() {
    return new PDErrorHandler<>(
        r -> r.getHeader().hasError() ? buildFromPdpbError(r.getHeader().getError()) : null, this);
  }

  @Override
  public Store getStore(BackOffer backOffer, long storeId) {
    return callWithRetry(
            backOffer, PDGrpc.getGetStoreMethod(), buildGetStoreReq(storeId), buildPDErrorHandler())
        .getStore();
  }

  @Override
  public Future<Store> getStoreAsync(BackOffer backOffer, long storeId) {
    FutureObserver<Store, GetStoreResponse> responseObserver =
        new FutureObserver<>(GetStoreResponse::getStore);

    callAsyncWithRetry(
        backOffer,
        PDGrpc.getGetStoreMethod(),
        buildGetStoreReq(storeId),
        responseObserver,
        buildPDErrorHandler());
    return responseObserver.getFuture();
  }

  @Override
  public List<Store> getAllStores(BackOffer backOffer) {
    return callWithRetry(
            backOffer,
            PDGrpc.getGetAllStoresMethod(),
            buildGetAllStoresReq(),
            new PDErrorHandler<>(
                r -> r.getHeader().hasError() ? buildFromPdpbError(r.getHeader().getError()) : null,
                this))
        .getStoresList();
  }

  @Override
  public TiConfiguration.ReplicaRead getReplicaRead() {
    return conf.getReplicaRead();
  }

  @Override
  public void close() throws InterruptedException {
    etcdClient.close();
    if (service != null) {
      service.shutdownNow();
    }
    if (tiflashReplicaService != null) {
      tiflashReplicaService.shutdownNow();
    }
    if (channelFactory != null) {
      channelFactory.close();
    }
  }

  @VisibleForTesting
  RequestHeader getHeader() {
    return header;
  }

  @VisibleForTesting
  LeaderWrapper getLeaderWrapper() {
    return leaderWrapper;
  }

  private GetMembersResponse getMembers(URI uri) {
    try {
      ManagedChannel probChan = channelFactory.getChannel(uriToAddr(uri), hostMapping);
      PDGrpc.PDBlockingStub stub = PDGrpc.newBlockingStub(probChan);
      GetMembersRequest request =
          GetMembersRequest.newBuilder().setHeader(RequestHeader.getDefaultInstance()).build();
      GetMembersResponse resp = stub.getMembers(request);
      // check if the response contains a valid leader
      if (resp != null && resp.getLeader().getMemberId() == 0) {
        return null;
      }
      return resp;
    } catch (Exception e) {
      logger.warn("failed to get member from pd server.", e);
    }
    return null;
  }

  synchronized boolean switchLeader(List<String> leaderURLs) {
    if (leaderURLs.isEmpty()) return false;
    String leaderUrlStr = leaderURLs.get(0);
    // TODO: Why not strip protocol info on server side since grpc does not need it
    if (leaderWrapper != null && leaderUrlStr.equals(leaderWrapper.getLeaderInfo())) {
      return true;
    }
    // switch leader
    return createLeaderWrapper(leaderUrlStr);
  }

  private synchronized boolean createLeaderWrapper(String leaderUrlStr) {
    try {
      URI newLeader = addrToUri(leaderUrlStr);
      leaderUrlStr = uriToAddr(newLeader);
      if (leaderWrapper != null && leaderUrlStr.equals(leaderWrapper.getLeaderInfo())) {
        return true;
      }

      // create new Leader
      ManagedChannel clientChannel = channelFactory.getChannel(leaderUrlStr, hostMapping);
      leaderWrapper =
          new LeaderWrapper(
              leaderUrlStr,
              PDGrpc.newBlockingStub(clientChannel),
              PDGrpc.newStub(clientChannel),
              System.nanoTime());
    } catch (IllegalArgumentException e) {
      logger.error("Error updating leader. " + leaderUrlStr, e);
      return false;
    }
    logger.info(String.format("Switched to new leader: %s", leaderWrapper));
    return true;
  }

  public void updateLeader() {
    for (URI url : this.pdAddrs) {
      // since resp is null, we need update leader's address by walking through all pd server.
      GetMembersResponse resp = getMembers(url);
      if (resp == null) {
        continue;
      }
      // if leader is switched, just return.
      if (switchLeader(resp.getLeader().getClientUrlsList())) {
        return;
      }
    }
    throw new TiClientInternalException(
        "already tried all address on file, but not leader found yet.");
  }

  public void updateTiFlashReplicaStatus() {
    ByteSequence prefix =
        ByteSequence.from(TIFLASH_TABLE_SYNC_PROGRESS_PATH, StandardCharsets.UTF_8);
    for (int i = 0; i < 5; i++) {
      CompletableFuture<GetResponse> resp;
      try {
        resp =
            etcdClient.getKVClient().get(prefix, GetOption.newBuilder().withPrefix(prefix).build());
      } catch (Exception e) {
        logger.info("get tiflash table replica sync progress failed, continue checking.", e);
        continue;
      }
      GetResponse getResp;
      try {
        getResp = resp.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        continue;
      } catch (ExecutionException e) {
        throw new GrpcException("failed to update tiflash replica", e);
      }
      ConcurrentMap<Long, Double> progressMap = new ConcurrentHashMap<>();
      for (KeyValue kv : getResp.getKvs()) {
        long tableId;
        try {
          tableId =
              Long.parseLong(
                  kv.getKey().toString().substring(TIFLASH_TABLE_SYNC_PROGRESS_PATH.length()));
        } catch (Exception e) {
          logger.info(
              "invalid tiflash table replica sync progress key. key = " + kv.getKey().toString());
          continue;
        }
        double progress;
        try {
          progress = Double.parseDouble(kv.getValue().toString());
        } catch (Exception e) {
          logger.info(
              "invalid tiflash table replica sync progress value. value = "
                  + kv.getValue().toString());
          continue;
        }
        progressMap.put(tableId, progress);
      }
      tiflashReplicaMap = progressMap;
      break;
    }
  }

  public double getTiFlashReplicaProgress(long tableId) {
    return tiflashReplicaMap.getOrDefault(tableId, 0.0);
  }

  @Override
  protected PDBlockingStub getBlockingStub() {
    if (leaderWrapper == null) {
      throw new GrpcException("PDClient may not be initialized");
    }
    return leaderWrapper.getBlockingStub().withDeadlineAfter(getTimeout(), TimeUnit.MILLISECONDS);
  }

  @Override
  protected PDStub getAsyncStub() {
    if (leaderWrapper == null) {
      throw new GrpcException("PDClient may not be initialized");
    }
    return leaderWrapper.getAsyncStub().withDeadlineAfter(getTimeout(), TimeUnit.MILLISECONDS);
  }

  private void initCluster() {
    GetMembersResponse resp = null;
    List<URI> pdAddrs = getConf().getPdAddrs();
    this.pdAddrs = pdAddrs;
    this.etcdClient =
        Client.builder()
            .endpoints(pdAddrs)
            .executorService(
                Executors.newCachedThreadPool(
                    new ThreadFactoryBuilder()
                        .setNameFormat("etcd-conn-manager-pool-%d")
                        .setDaemon(true)
                        .build()))
            .build();
    this.hostMapping = new HostMapping(this.etcdClient, conf.getNetworkMappingName());
    for (URI u : pdAddrs) {
      resp = getMembers(u);
      if (resp != null) {
        break;
      }
    }
    checkNotNull(resp, "Failed to init client for PD cluster.");
    long clusterId = resp.getHeader().getClusterId();
    header = RequestHeader.newBuilder().setClusterId(clusterId).build();
    tsoReq = TsoRequest.newBuilder().setHeader(header).setCount(1).build();
    this.tiflashReplicaMap = new ConcurrentHashMap<>();
    createLeaderWrapper(resp.getLeader().getClientUrls(0));
    service =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("PDClient-update-leader-pool-%d")
                .setDaemon(true)
                .build());
    service.scheduleAtFixedRate(
        () -> {
          // Wrap this with a try catch block in case schedule update fails
          try {
            updateLeader();
          } catch (Exception e) {
            logger.warn("Update leader failed", e);
          }
        },
        1,
        1,
        TimeUnit.MINUTES);
    tiflashReplicaService =
        Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("PDClient-tiflash-replica-pool-%d")
                .setDaemon(true)
                .build());
    tiflashReplicaService.scheduleAtFixedRate(
        this::updateTiFlashReplicaStatus, 10, 10, TimeUnit.SECONDS);
  }

  static class LeaderWrapper {
    private final String leaderInfo;
    private final PDBlockingStub blockingStub;
    private final PDStub asyncStub;
    private final long createTime;

    LeaderWrapper(
        String leaderInfo,
        PDGrpc.PDBlockingStub blockingStub,
        PDGrpc.PDStub asyncStub,
        long createTime) {
      this.leaderInfo = leaderInfo;
      this.blockingStub = blockingStub;
      this.asyncStub = asyncStub;
      this.createTime = createTime;
    }

    String getLeaderInfo() {
      return leaderInfo;
    }

    PDBlockingStub getBlockingStub() {
      return blockingStub;
    }

    PDStub getAsyncStub() {
      return asyncStub;
    }

    long getCreateTime() {
      return createTime;
    }

    @Override
    public String toString() {
      return "[leaderInfo: " + leaderInfo + "]";
    }
  }
}
