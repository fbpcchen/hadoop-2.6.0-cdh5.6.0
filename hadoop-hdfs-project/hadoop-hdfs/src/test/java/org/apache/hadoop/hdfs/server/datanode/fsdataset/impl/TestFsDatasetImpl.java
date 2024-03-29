/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.datanode.fsdataset.impl;

import com.google.common.collect.Lists;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystemTestHelper;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.StorageType;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.hdfs.server.datanode.BlockScanner;
import org.apache.hadoop.hdfs.server.datanode.DNConf;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.datanode.DataStorage;
import org.apache.hadoop.hdfs.server.datanode.FinalizedReplica;
import org.apache.hadoop.hdfs.server.datanode.ReplicaHandler;
import org.apache.hadoop.hdfs.server.datanode.ReplicaInfo;
import org.apache.hadoop.hdfs.server.datanode.ShortCircuitRegistry;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsDatasetSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeReference;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.RoundRobinVolumeChoosingPolicy;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.DiskChecker;
import org.apache.hadoop.util.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.apache.hadoop.hdfs.DFSConfigKeys.DFS_DATANODE_SCAN_PERIOD_HOURS_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestFsDatasetImpl {
  private static final String BASE_DIR =
      new FileSystemTestHelper().getTestRootDir();
  private static final int NUM_INIT_VOLUMES = 2;
  private static final String CLUSTER_ID = "cluser-id";
  private static final String[] BLOCK_POOL_IDS = {"bpid-0", "bpid-1"};

  // Use to generate storageUuid
  private static final DataStorage dsForStorageUuid = new DataStorage(
      new StorageInfo(HdfsServerConstants.NodeType.DATA_NODE));

  private Configuration conf;
  private DataNode datanode;
  private DataStorage storage;
  private FsDatasetImpl dataset;
  
  private final static String BLOCKPOOL = "BP-TEST";

  private static Storage.StorageDirectory createStorageDirectory(File root) {
    Storage.StorageDirectory sd = new Storage.StorageDirectory(root);
    dsForStorageUuid.createStorageID(sd, false);
    return sd;
  }

  private static void createStorageDirs(DataStorage storage, Configuration conf,
      int numDirs) throws IOException {
    List<Storage.StorageDirectory> dirs =
        new ArrayList<Storage.StorageDirectory>();
    List<String> dirStrings = new ArrayList<String>();
    for (int i = 0; i < numDirs; i++) {
      File loc = new File(BASE_DIR + "/data" + i);
      dirStrings.add(loc.toString());
      loc.mkdirs();
      dirs.add(createStorageDirectory(loc));
      when(storage.getStorageDir(i)).thenReturn(dirs.get(i));
    }

    String dataDir = StringUtils.join(",", dirStrings);
    conf.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY, dataDir);
    when(storage.dirIterator()).thenReturn(dirs.iterator());
    when(storage.getNumStorageDirs()).thenReturn(numDirs);
  }

  private int getNumVolumes() {
    try (FsDatasetSpi.FsVolumeReferences volumes =
        dataset.getFsVolumeReferences()) {
      return volumes.size();
    } catch (IOException e) {
      return 0;
    }
  }

  @Before
  public void setUp() throws IOException {
    datanode = mock(DataNode.class);
    storage = mock(DataStorage.class);
    this.conf = new Configuration();
    this.conf.setLong(DFS_DATANODE_SCAN_PERIOD_HOURS_KEY, 0);
    final DNConf dnConf = new DNConf(conf);

    when(datanode.getConf()).thenReturn(conf);
    when(datanode.getDnConf()).thenReturn(dnConf);
    final BlockScanner disabledBlockScanner = new BlockScanner(datanode, conf);
    when(datanode.getBlockScanner()).thenReturn(disabledBlockScanner);
    final ShortCircuitRegistry shortCircuitRegistry =
        new ShortCircuitRegistry(conf);
    when(datanode.getShortCircuitRegistry()).thenReturn(shortCircuitRegistry);

    createStorageDirs(storage, conf, NUM_INIT_VOLUMES);
    dataset = new FsDatasetImpl(datanode, storage, conf);
    for (String bpid : BLOCK_POOL_IDS) {
      dataset.addBlockPool(bpid, conf);
    }

    assertEquals(NUM_INIT_VOLUMES, getNumVolumes());
    assertEquals(0, dataset.getNumFailedVolumes());
  }

  @Test
  public void testAddVolumes() throws IOException {
    final int numNewVolumes = 3;
    final int numExistingVolumes = getNumVolumes();
    final int totalVolumes = numNewVolumes + numExistingVolumes;
    Set<String> expectedVolumes = new HashSet<String>();
    List<NamespaceInfo> nsInfos = Lists.newArrayList();
    for (String bpid : BLOCK_POOL_IDS) {
      nsInfos.add(new NamespaceInfo(0, CLUSTER_ID, bpid, 1));
    }
    for (int i = 0; i < numNewVolumes; i++) {
      String path = BASE_DIR + "/newData" + i;
      expectedVolumes.add(path);
      StorageLocation loc = StorageLocation.parse(path);
      Storage.StorageDirectory sd = createStorageDirectory(new File(path));
      DataStorage.VolumeBuilder builder =
          new DataStorage.VolumeBuilder(storage, sd);
      when(storage.prepareVolume(eq(datanode), eq(loc.getFile()),
          anyListOf(NamespaceInfo.class)))
          .thenReturn(builder);

      dataset.addVolume(loc, nsInfos);
    }

    assertEquals(totalVolumes, getNumVolumes());
    assertEquals(totalVolumes, dataset.storageMap.size());

    Set<String> actualVolumes = new HashSet<String>();
    try (FsDatasetSpi.FsVolumeReferences volumes =
        dataset.getFsVolumeReferences()) {
      for (int i = 0; i < numNewVolumes; i++) {
        actualVolumes.add(volumes.get(numExistingVolumes + i).getBasePath());
      }
    }
    assertEquals(actualVolumes, expectedVolumes);
  }

  @Test(timeout = 30000)
  public void testRemoveVolumes() throws IOException {
    // Feed FsDataset with block metadata.
    final int NUM_BLOCKS = 100;
    for (int i = 0; i < NUM_BLOCKS; i++) {
      String bpid = BLOCK_POOL_IDS[NUM_BLOCKS % BLOCK_POOL_IDS.length];
      ExtendedBlock eb = new ExtendedBlock(bpid, i);
      try (ReplicaHandler replica =
          dataset.createRbw(StorageType.DEFAULT, eb, false)) {
      }
    }
    final String[] dataDirs =
        conf.get(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY).split(",");
    final String volumePathToRemove = dataDirs[0];
    Set<File> volumesToRemove = new HashSet<>();
    volumesToRemove.add(StorageLocation.parse(volumePathToRemove).getFile());

    dataset.removeVolumes(volumesToRemove, true);
    int expectedNumVolumes = dataDirs.length - 1;
    assertEquals("The volume has been removed from the volumeList.",
        expectedNumVolumes, getNumVolumes());
    assertEquals("The volume has been removed from the storageMap.",
        expectedNumVolumes, dataset.storageMap.size());

    try {
      dataset.asyncDiskService.execute(volumesToRemove.iterator().next(),
          new Runnable() {
            @Override
            public void run() {}
          });
      fail("Expect RuntimeException: the volume has been removed from the "
           + "AsyncDiskService.");
    } catch (RuntimeException e) {
      GenericTestUtils.assertExceptionContains("Cannot find root", e);
    }

    int totalNumReplicas = 0;
    for (String bpid : dataset.volumeMap.getBlockPoolList()) {
      totalNumReplicas += dataset.volumeMap.size(bpid);
    }
    assertEquals("The replica infos on this volume has been removed from the "
                 + "volumeMap.", NUM_BLOCKS / NUM_INIT_VOLUMES,
                 totalNumReplicas);
  }

  @Test(timeout = 5000)
  public void testRemoveNewlyAddedVolume() throws IOException {
    final int numExistingVolumes = getNumVolumes();
    List<NamespaceInfo> nsInfos = new ArrayList<>();
    for (String bpid : BLOCK_POOL_IDS) {
      nsInfos.add(new NamespaceInfo(0, CLUSTER_ID, bpid, 1));
    }
    String newVolumePath = BASE_DIR + "/newVolumeToRemoveLater";
    StorageLocation loc = StorageLocation.parse(newVolumePath);

    Storage.StorageDirectory sd = createStorageDirectory(new File(newVolumePath));
    DataStorage.VolumeBuilder builder =
        new DataStorage.VolumeBuilder(storage, sd);
    when(storage.prepareVolume(eq(datanode), eq(loc.getFile()),
        anyListOf(NamespaceInfo.class)))
        .thenReturn(builder);

    dataset.addVolume(loc, nsInfos);
    assertEquals(numExistingVolumes + 1, getNumVolumes());

    when(storage.getNumStorageDirs()).thenReturn(numExistingVolumes + 1);
    when(storage.getStorageDir(numExistingVolumes)).thenReturn(sd);
    Set<File> volumesToRemove = new HashSet<>();
    volumesToRemove.add(loc.getFile());
    dataset.removeVolumes(volumesToRemove, true);
    assertEquals(numExistingVolumes, getNumVolumes());
  }

  @Test(timeout = 5000)
  public void testChangeVolumeWithRunningCheckDirs() throws IOException {
    RoundRobinVolumeChoosingPolicy<FsVolumeImpl> blockChooser =
        new RoundRobinVolumeChoosingPolicy<>();
    conf.setLong(DFSConfigKeys.DFS_DATANODE_SCAN_PERIOD_HOURS_KEY, -1);
    final BlockScanner blockScanner = new BlockScanner(datanode, conf);
    final FsVolumeList volumeList = new FsVolumeList(
        Collections.<VolumeFailureInfo>emptyList(), blockScanner, blockChooser);
    final List<FsVolumeImpl> oldVolumes = new ArrayList<>();

    // Initialize FsVolumeList with 5 mock volumes.
    final int NUM_VOLUMES = 5;
    for (int i = 0; i < NUM_VOLUMES; i++) {
      FsVolumeImpl volume = mock(FsVolumeImpl.class);
      oldVolumes.add(volume);
      when(volume.getBasePath()).thenReturn("data" + i);
      FsVolumeReference ref = mock(FsVolumeReference.class);
      when(ref.getVolume()).thenReturn(volume);
      volumeList.addVolume(ref);
    }

    // When call checkDirs() on the 2nd volume, anther "thread" removes the 5th
    // volume and add another volume. It does not affect checkDirs() running.
    final FsVolumeImpl newVolume = mock(FsVolumeImpl.class);
    final FsVolumeReference newRef = mock(FsVolumeReference.class);
    when(newRef.getVolume()).thenReturn(newVolume);
    when(newVolume.getBasePath()).thenReturn("data4");
    FsVolumeImpl blockedVolume = volumeList.getVolumes().get(1);
    doAnswer(new Answer() {
      @Override
      public Object answer(InvocationOnMock invocationOnMock)
          throws Throwable {
        volumeList.removeVolume(new File("data4"), false);
        volumeList.addVolume(newRef);
        return null;
      }
    }).when(blockedVolume).checkDirs();

    FsVolumeImpl brokenVolume = volumeList.getVolumes().get(2);
    doThrow(new DiskChecker.DiskErrorException("broken"))
        .when(brokenVolume).checkDirs();

    volumeList.checkDirs();

    // Since FsVolumeImpl#checkDirs() get a snapshot of the list of volumes
    // before running removeVolume(), it is supposed to run checkDirs() on all
    // the old volumes.
    for (FsVolumeImpl volume : oldVolumes) {
      verify(volume).checkDirs();
    }
    // New volume is not visible to checkDirs() process.
    verify(newVolume, never()).checkDirs();
    assertTrue(volumeList.getVolumes().contains(newVolume));
    assertFalse(volumeList.getVolumes().contains(brokenVolume));
    assertEquals(NUM_VOLUMES - 1, volumeList.getVolumes().size());
  }

  @Test
  public void testAddVolumeFailureReleasesInUseLock() throws IOException {
    FsDatasetImpl spyDataset = spy(dataset);
    FsVolumeImpl mockVolume = mock(FsVolumeImpl.class);
    File badDir = new File(BASE_DIR, "bad");
    badDir.mkdirs();
    doReturn(mockVolume).when(spyDataset)
        .createFsVolume(anyString(), any(File.class), any(StorageType.class));
    doThrow(new IOException("Failed to getVolumeMap()"))
      .when(mockVolume).getVolumeMap(
        anyString(),
        any(ReplicaMap.class),
        any(RamDiskReplicaLruTracker.class));

    Storage.StorageDirectory sd = createStorageDirectory(badDir);
    sd.lock();
    DataStorage.VolumeBuilder builder = new DataStorage.VolumeBuilder(storage, sd);
    when(storage.prepareVolume(eq(datanode), eq(badDir),
        Matchers.<List<NamespaceInfo>>any()))
        .thenReturn(builder);

    StorageLocation location = StorageLocation.parse(badDir.toString());
    List<NamespaceInfo> nsInfos = Lists.newArrayList();
    for (String bpid : BLOCK_POOL_IDS) {
      nsInfos.add(new NamespaceInfo(0, CLUSTER_ID, bpid, 1));
    }

    try {
      spyDataset.addVolume(location, nsInfos);
      fail("Expect to throw MultipleIOException");
    } catch (MultipleIOException e) {
    }

    FsDatasetTestUtil.assertFileLockReleased(badDir.toString());
  }

  @Test
  public void testDuplicateReplicaResolution() throws IOException {
    FsVolumeImpl fsv1 = Mockito.mock(FsVolumeImpl.class);
    FsVolumeImpl fsv2 = Mockito.mock(FsVolumeImpl.class);

    File f1 = new File("d1/block");
    File f2 = new File("d2/block");

    ReplicaInfo replicaOlder = new FinalizedReplica(1,1,1,fsv1,f1);
    ReplicaInfo replica = new FinalizedReplica(1,2,2,fsv1,f1);
    ReplicaInfo replicaSame = new FinalizedReplica(1,2,2,fsv1,f1);
    ReplicaInfo replicaNewer = new FinalizedReplica(1,3,3,fsv1,f1);

    ReplicaInfo replicaOtherOlder = new FinalizedReplica(1,1,1,fsv2,f2);
    ReplicaInfo replicaOtherSame = new FinalizedReplica(1,2,2,fsv2,f2);
    ReplicaInfo replicaOtherNewer = new FinalizedReplica(1,3,3,fsv2,f2);

    // equivalent path so don't remove either
    assertNull(BlockPoolSlice.selectReplicaToDelete(replicaSame, replica));
    assertNull(BlockPoolSlice.selectReplicaToDelete(replicaOlder, replica));
    assertNull(BlockPoolSlice.selectReplicaToDelete(replicaNewer, replica));

    // keep latest found replica
    assertSame(replica,
        BlockPoolSlice.selectReplicaToDelete(replicaOtherSame, replica));
    assertSame(replicaOtherOlder,
        BlockPoolSlice.selectReplicaToDelete(replicaOtherOlder, replica));
    assertSame(replica,
        BlockPoolSlice.selectReplicaToDelete(replicaOtherNewer, replica));
  }
  
  @Test
  public void testDeletingBlocks() throws IOException {
    HdfsConfiguration conf = new HdfsConfiguration();
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    try {
      cluster.waitActive();
      DataNode dn = cluster.getDataNodes().get(0);
      
      FsDatasetSpi<?> ds = DataNodeTestUtils.getFSDataset(dn);
      ds.addBlockPool(BLOCKPOOL, conf);
      FsVolumeImpl vol;
      try (FsDatasetSpi.FsVolumeReferences volumes = ds.getFsVolumeReferences()) {
        vol = (FsVolumeImpl)volumes.get(0);
      }

      ExtendedBlock eb;
      ReplicaInfo info;
      List<Block> blockList = new ArrayList<>();
      for (int i = 1; i <= 63; i++) {
        eb = new ExtendedBlock(BLOCKPOOL, i, 1, 1000 + i);
        cluster.getFsDatasetTestUtils(0).createFinalizedReplica(eb);
        blockList.add(eb.getLocalBlock());
      }
      ds.invalidate(BLOCKPOOL, blockList.toArray(new Block[0]));
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        // Nothing to do
      }
      assertTrue(ds.isDeletingBlock(BLOCKPOOL, blockList.get(0).getBlockId()));

      blockList.clear();
      eb = new ExtendedBlock(BLOCKPOOL, 64, 1, 1064);
      cluster.getFsDatasetTestUtils(0).createFinalizedReplica(eb);
      blockList.add(eb.getLocalBlock());
      ds.invalidate(BLOCKPOOL, blockList.toArray(new Block[0]));
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        // Nothing to do
      }
      assertFalse(ds.isDeletingBlock(BLOCKPOOL, blockList.get(0).getBlockId()));
    } finally {
      cluster.shutdown();
    }
  }
}
