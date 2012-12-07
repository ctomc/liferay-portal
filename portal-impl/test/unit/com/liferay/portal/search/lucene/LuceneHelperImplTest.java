/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.search.lucene;

import com.liferay.portal.cluster.AddressImpl;
import com.liferay.portal.kernel.cluster.Address;
import com.liferay.portal.kernel.cluster.ClusterEvent;
import com.liferay.portal.kernel.cluster.ClusterEventListener;
import com.liferay.portal.kernel.cluster.ClusterExecutor;
import com.liferay.portal.kernel.cluster.ClusterExecutorUtil;
import com.liferay.portal.kernel.cluster.ClusterMessageType;
import com.liferay.portal.kernel.cluster.ClusterNode;
import com.liferay.portal.kernel.cluster.ClusterNodeResponse;
import com.liferay.portal.kernel.cluster.ClusterRequest;
import com.liferay.portal.kernel.cluster.ClusterResponseCallback;
import com.liferay.portal.kernel.cluster.FutureClusterResponses;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.io.unsync.UnsyncBufferedReader;
import com.liferay.portal.kernel.test.JDKLoggerTestUtil;
import com.liferay.portal.kernel.util.MethodHandler;
import com.liferay.portal.kernel.util.MethodKey;
import com.liferay.portal.kernel.util.SocketUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.uuid.PortalUUIDUtil;
import com.liferay.portal.security.auth.TransientTokenUtil;
import com.liferay.portal.test.AdviseWith;
import com.liferay.portal.test.AspectJMockingNewClassLoaderJUnitTestRunner;
import com.liferay.portal.util.PortalImpl;
import com.liferay.portal.util.PortalInstances;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PropsValues;
import com.liferay.portal.uuid.PortalUUIDImpl;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import java.nio.channels.ServerSocketChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author Tina Tian
 */
@RunWith(AspectJMockingNewClassLoaderJUnitTestRunner.class)
public class LuceneHelperImplTest {

	@Before
	public void setUp() throws Exception {
		PortalUtil portalUtil = new PortalUtil();

		portalUtil.setPortal(new PortalImpl());

		PortalUUIDUtil portalUUIDUtil = new PortalUUIDUtil();

		portalUUIDUtil.setPortalUUID(new PortalUUIDImpl());

		PortalInstances.addCompanyId(_TEST_COMPANY_ID);

		_localhost = InetAddress.getLocalHost();

		_mockClusterExecutor = new MockClusterExecutor();

		ClusterExecutorUtil clusterExecutorUtil = new ClusterExecutorUtil();

		clusterExecutorUtil.setClusterExecutor(_mockClusterExecutor);

		Class<LuceneHelperImpl> clazz = LuceneHelperImpl.class;

		Constructor<LuceneHelperImpl> luceneHelperImplConstructor =
			clazz.getDeclaredConstructor();

		luceneHelperImplConstructor.setAccessible(true);

		_luceneHelperImpl = luceneHelperImplConstructor.newInstance();

		Field indexAccessorsField = clazz.getDeclaredField("_indexAccessors");

		indexAccessorsField.setAccessible(true);

		_mockIndexAccessor = new MockIndexAccessor();

		Map<Long, IndexAccessor> indexAccessorMap =
			(Map<Long, IndexAccessor>)indexAccessorsField.get(
				_luceneHelperImpl);

		indexAccessorMap.put(_TEST_COMPANY_ID, _mockIndexAccessor);

		LuceneHelperUtil luceneHelperUtil = new LuceneHelperUtil();

		luceneHelperUtil.setLuceneHelper(_luceneHelperImpl);

		_testClusterNode = new ClusterNode(_TEST_CLASSNODE_ID, _localhost);
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexClusterEventListener1() throws Exception {
		MockServer mockServer = new MockServer();

		mockServer.start();

		int port = mockServer.getPort();

		_mockClusterExecutor.setNodeNumber(2);
		_mockClusterExecutor.setPort(port);

		_testClusterNode.setPort(port);

		JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.OFF);

		ClusterEvent clusterEvent = ClusterEvent.join(_testClusterNode);

		_fireClusterEventListeners(clusterEvent);

		byte[] responseMessage = _mockIndexAccessor.getResponseMessage();

		Assert.assertArrayEquals(_RESPONSE_MESSAGE, responseMessage);

		mockServer.join();
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexClusterEventListener2() throws Exception {
		_mockClusterExecutor.setNodeNumber(2);
		_mockClusterExecutor.setThrowException(true);

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.SEVERE);

		ClusterEvent clusterEvent = ClusterEvent.join(_testClusterNode);

		_fireClusterEventListeners(clusterEvent);

		Assert.assertEquals(1, logRecords.size());

		_assertLogger(
			logRecords.get(0),
			"Unable to load indexes for company " + _TEST_COMPANY_ID,
			SystemException.class);
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexClusterEventListener3() throws Exception {
		_mockClusterExecutor.setNodeNumber(3);

		//Debug is enabled

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		ClusterEvent clusterEvent = ClusterEvent.join(_testClusterNode);

		_fireClusterEventListeners(clusterEvent);

		Assert.assertEquals(1, logRecords.size());

		_assertLogger(
			logRecords.get(0),
			"Number of original cluster members is greater than one", null);

		//Debug is not enabled

		logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.INFO);

		_fireClusterEventListeners(clusterEvent);

		Assert.assertTrue(logRecords.isEmpty());
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster1() throws Exception {
		MockServer mockServer = new MockServer();

		mockServer.start();

		_mockClusterExecutor.setNodeNumber(2);
		_mockClusterExecutor.setPort(mockServer.getPort());

		JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.INFO);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		byte[] responseMessage = _mockIndexAccessor.getResponseMessage();

		Assert.assertArrayEquals(_RESPONSE_MESSAGE, responseMessage);

		mockServer.join();
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster2() throws Exception {
		_mockClusterExecutor.setNodeNumber(3);
		_mockClusterExecutor.setAutoResponse(false);

		// Debug is enabled

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertEquals(2, logRecords.size());

		_assertLogger(
			logRecords.get(0), "Unable to get cluster node response in 10000" +
				TimeUnit.MILLISECONDS,
			null);

		_assertLogger(
			logRecords.get(1), "Unable to get cluster node response in 10000" +
				TimeUnit.MILLISECONDS,
			null);

		// Debug is not enabled

		logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.INFO);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertTrue(logRecords.isEmpty());
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster3() throws Exception {
		_mockClusterExecutor.setNodeNumber(2);

		// Debug is enabled

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertEquals(1, logRecords.size());

		_assertLogger(logRecords.get(0), "invalid port", null);

		// Debug is not enabled

		logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.INFO);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertTrue(logRecords.isEmpty());
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster4() throws Exception {
		_mockClusterExecutor.setNodeNumber(2);
		_mockClusterExecutor.setPort(1024);

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertEquals(2, logRecords.size());

		_assertLogger(
			logRecords.get(0),
			"Start loading lucene index files from cluster node", null);

		_assertLogger(
			logRecords.get(1),
			"Unable to load index for company " + _TEST_COMPANY_ID,
			SystemException.class);
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster5() throws Exception {
		_mockClusterExecutor.setNodeNumber(2);
		_mockClusterExecutor.setInvokeMethodThrowException(true);
		_mockClusterExecutor.setPort(1024);

		// Debug is enabled

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertEquals(1, logRecords.size());

		_assertLogger(
			logRecords.get(0),
			"Suppress exception caused by remote method invocation",
			Exception.class);

		// Debug is not enabled

		logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.INFO);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertTrue(logRecords.isEmpty());
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, EnableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster6() throws Exception {
		_mockClusterExecutor.setNodeNumber(1);

		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		_luceneHelperImpl.loadIndexesFromCluster(_TEST_COMPANY_ID);

		Assert.assertEquals(1, logRecords.size());

		_assertLogger(
			logRecords.get(0),
			"Do not load indexes because there is either one portal instance " +
				"or no portal instances in the cluster",
			null);
	}

	@AdviseWith(
		adviceClasses = {
			DisableIndexOnStartUpAdvice.class, DisableClusterLinkAdvice.class,
			EnableLuceneReplicateWriteAdvice.class
		}
	)
	@Test
	public void testLoadIndexFromCluster7() throws Exception {
		List<LogRecord> logRecords = JDKLoggerTestUtil.configureJDKLogger(
			LuceneHelperImpl.class.getName(), Level.FINE);

		_luceneHelperImpl.loadIndexesFromCluster(0);

		Assert.assertEquals(1, logRecords.size());

		_assertLogger(
			logRecords.get(0), "Load index from cluster is not enabled", null);
	}

	@Aspect
	public static class DisableClusterLinkAdvice {

		@Around(
			"set(* com.liferay.portal.util.PropsValues.CLUSTER_LINK_ENABLED)")
		public Object disableClusterLink(
				ProceedingJoinPoint proceedingJoinPoint)
			throws Throwable {

			return proceedingJoinPoint.proceed(new Object[]{Boolean.FALSE});
		}

	}

	@Aspect
	public static class DisableIndexOnStartUpAdvice {

		@Around(
			"set(* com.liferay.portal.util.PropsValues.INDEX_ON_STARTUP)")
		public Object disableIndexOnStartUp(
				ProceedingJoinPoint proceedingJoinPoint)
			throws Throwable {

			return proceedingJoinPoint.proceed(new Object[]{Boolean.FALSE});
		}

	}

	@Aspect
	public static class EnableClusterLinkAdvice {

		@Around(
			"set(* com.liferay.portal.util.PropsValues.CLUSTER_LINK_ENABLED)")
		public Object enableClusterLink(ProceedingJoinPoint proceedingJoinPoint)
			throws Throwable {

			return proceedingJoinPoint.proceed(new Object[]{Boolean.TRUE});
		}

	}

	@Aspect
	public static class EnableLuceneReplicateWriteAdvice {

		@Around(
			"set(* com.liferay.portal.util.PropsValues.LUCENE_REPLICATE_WRITE)")
		public Object enableLuceneReplicateWrite(
				ProceedingJoinPoint proceedingJoinPoint)
			throws Throwable {

			return proceedingJoinPoint.proceed(new Object[]{Boolean.TRUE});
		}

	}

	private void _assertLogger(
		LogRecord logRecord, String message, Class<?> exceptionClass) {

		Assert.assertTrue(logRecord.getMessage().contains(message));

		if (exceptionClass == null) {
			Assert.assertNull(logRecord.getThrown());
		}
		else {
			Throwable throwable = logRecord.getThrown();

			Assert.assertEquals(exceptionClass, throwable.getClass());
		}
	}

	private void _fireClusterEventListeners(ClusterEvent clusterEvent) {
		ClusterExecutor clusterExecutor =
			ClusterExecutorUtil.getClusterExecutor();

		List<ClusterEventListener> clusterEventListeners =
			clusterExecutor.getClusterEventListeners();

		for (
			ClusterEventListener clusterEventListener : clusterEventListeners) {

			clusterEventListener.processClusterEvent(clusterEvent);
		}
	}

	private static final byte[] _RESPONSE_MESSAGE =
		"response message".getBytes();
	private static final String _TEST_CLASSNODE_ID = "12345";
	private static final long _TEST_COMPANY_ID = 1;
	private static final long _TEST_LAST_GENERATION = 1;

	private InetAddress _localhost;
	private LuceneHelperImpl _luceneHelperImpl;
	private MockClusterExecutor _mockClusterExecutor;
	private MockIndexAccessor _mockIndexAccessor;
	private ClusterNode _testClusterNode;

	private class MockAddress implements org.jgroups.Address {

		public int compareTo(org.jgroups.Address jGroupsAddress) {
			return 0;
		}

		public void readExternal(ObjectInput objectInput) {
		}

		public void readFrom(DataInput dataInput) {
		}

		public int size() {
			return 0;
		}

		public void writeExternal(ObjectOutput objectOutput) {
		}

		public void writeTo(DataOutput dataOutput) {
		}

	}

	private class MockBlockingQueue<E> extends LinkedBlockingQueue<E> {

		public MockBlockingQueue(BlockingQueue<E> blockingQueue) {
			_blockingQueue = blockingQueue;
		}

		public E poll(long timeout, TimeUnit unit) throws InterruptedException {
			return _blockingQueue.poll(1000, TimeUnit.MILLISECONDS);
		}

		private BlockingQueue<E> _blockingQueue;

	}

	private class MockClusterExecutor implements ClusterExecutor {

		public void addClusterEventListener(
			ClusterEventListener clusterEventListener) {

			_clusterEventListeners.add(clusterEventListener);
		}

		public void destroy() {
			_addresses.clear();
			_clusterEventListeners.clear();
		}

		public FutureClusterResponses execute(ClusterRequest clusterRequest)
			throws SystemException {

			if (_throwException) {
				throw new SystemException();
			}

			if (!_autoResponse) {
				return new FutureClusterResponses(Collections.EMPTY_LIST);
			}

			FutureClusterResponses futureClusterResponses =
				new FutureClusterResponses(_addresses);

			for (Address address : _addresses) {
				ClusterNodeResponse clusterNodeResponse =
					new ClusterNodeResponse();

				clusterNodeResponse.setAddress(address);
				clusterNodeResponse.setClusterMessageType(
					ClusterMessageType.EXECUTE);
				clusterNodeResponse.setMulticast(clusterRequest.isMulticast());
				clusterNodeResponse.setUuid(clusterRequest.getUuid());

				ClusterNode clusterNode = new ClusterNode(
					String.valueOf(System.currentTimeMillis()), _localhost);

				clusterNode.setPort(_port);

				clusterNodeResponse.setClusterNode(clusterNode);

				try {
					clusterNodeResponse.setResult(
						_invoke(clusterRequest.getMethodHandler()));
				}
				catch (Exception e) {
					clusterNodeResponse.setException(e);
				}

				futureClusterResponses.addClusterNodeResponse(
					clusterNodeResponse);
			}

			return futureClusterResponses;
		}

		public void execute(
				ClusterRequest clusterRequest,
				ClusterResponseCallback clusterResponseCallback)
			throws SystemException {

			FutureClusterResponses futureClusterResponses = execute(
				clusterRequest);

			try {
				BlockingQueue<ClusterNodeResponse> blockingQueue =
					futureClusterResponses.get().getClusterResponses();

				MockBlockingQueue mockBlockingQueue =
					new MockBlockingQueue<ClusterNodeResponse>(blockingQueue);

				clusterResponseCallback.callback(mockBlockingQueue);
			}
			catch (InterruptedException ie) {
				throw new RuntimeException(ie);
			}
		}

		public void execute(
				ClusterRequest clusterRequest,
				ClusterResponseCallback clusterResponseCallback, long timeout,
				TimeUnit timeUnit)
			throws SystemException {

			FutureClusterResponses futureClusterResponses = execute(
				clusterRequest);

			try {
				clusterResponseCallback.callback(
					futureClusterResponses.get(
						timeout, timeUnit).getClusterResponses());
			}
			catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public List<ClusterEventListener> getClusterEventListeners() {
			return Collections.unmodifiableList(_clusterEventListeners);
		}

		public List<Address> getClusterNodeAddresses() {
			return Collections.unmodifiableList(_addresses);
		}

		public List<ClusterNode> getClusterNodes() {
			return Collections.EMPTY_LIST;
		}

		public ClusterNode getLocalClusterNode() {
			return null;
		}

		public Address getLocalClusterNodeAddress() {
			return _addresses.get(0);
		}

		public void initialize() {
		}

		public boolean isClusterNodeAlive(Address address) {
			return _addresses.contains(address);
		}

		public boolean isClusterNodeAlive(String clusterNodeId) {
			return false;
		}

		public boolean isEnabled() {
			return PropsValues.CLUSTER_LINK_ENABLED;
		}

		public void removeClusterEventListener(
			ClusterEventListener clusterEventListener) {

			_clusterEventListeners.remove(clusterEventListener);
		}

		public void setAutoResponse(boolean autoResponse) {
			_autoResponse = autoResponse;
		}

		public void setInvokeMethodThrowException(
			boolean invokeMethodThrowException) {

			_invokeMethodThrowException = invokeMethodThrowException;
		}

		public void setPort(int port) {
			_port = port;
		}

		public void setNodeNumber(int nodeNumber) {
			for (int i = 0; i < nodeNumber; i++) {
				_addresses.add(new AddressImpl(new MockAddress()));
			}
		}

		public void setThrowException(boolean throwException) {
			_throwException = throwException;
		}

		private Object _invoke(MethodHandler methodHandler) throws Exception {
			if (_invokeMethodThrowException) {
				throw new Exception();
			}

			MethodKey methodKey = methodHandler.getMethodKey();

			if (methodKey.equals(_createTokenMethodKey)) {
				long timeToLive = (Long)methodHandler.getArguments()[0];

				return TransientTokenUtil.createToken(timeToLive);
			}
			else if (methodKey.equals(_getLastGenerationMethodKey)) {
				return _TEST_LAST_GENERATION + 1;
			}

			return null;
		}

		private List<Address> _addresses = new ArrayList<Address>();
		private boolean _autoResponse = true;
		private List<ClusterEventListener> _clusterEventListeners =
			new ArrayList<ClusterEventListener>();
		private MethodKey _createTokenMethodKey = new MethodKey(
			TransientTokenUtil.class, "createToken", long.class);
		private MethodKey _getLastGenerationMethodKey = new MethodKey(
			LuceneHelperUtil.class, "getLastGeneration", long.class);
		private boolean _invokeMethodThrowException = false;
		private int _port = 0;
		private boolean _throwException = false;

	}

	private class MockIndexAccessor implements IndexAccessor {

		public void addDocument(Document document) {
		}

		public void close() {
		}

		public void delete() {
		}

		public void deleteDocuments(Term term) {
		}

		public void dumpIndex(OutputStream outputStream) {
		}

		public long getCompanyId() {
			return _TEST_COMPANY_ID;
		}

		public long getLastGeneration() {
			return _TEST_LAST_GENERATION;
		}

		public Directory getLuceneDir() {
			return null;
		}

		public void loadIndex(InputStream inputStream) throws IOException {
			_bytes = new byte[_RESPONSE_MESSAGE.length];

			int offset = 0;

			while (offset < _RESPONSE_MESSAGE.length) {
				int bytesRead = inputStream.read(
					_bytes, offset, _RESPONSE_MESSAGE.length - offset);

				if (bytesRead == -1) {
					break;
				}

				offset += bytesRead;
			}
		}

		public void updateDocument(Term term, Document document)
			throws IOException {
		}

		public byte[] getResponseMessage() {
			return _bytes;
		}

		private byte[] _bytes;

	}

	private class MockServer extends Thread {

		public MockServer() throws IOException {
			ServerSocketChannel serverSocketChannel =
				SocketUtil.createServerSocketChannel(_localhost, 1024, null);

			_serverSocket = serverSocketChannel.socket();
		}

		public int getPort() {
			return _serverSocket.getLocalPort();
		}

		@Override
		public void run() {
			Socket connection = null;

			try {
				connection = _serverSocket.accept();

				_serverSocket.close();

				UnsyncBufferedReader reader =
					new UnsyncBufferedReader(
						new InputStreamReader(connection.getInputStream()));

				String request = reader.readLine();

				connection.shutdownInput();

				if (!request.contains("/lucene/dump")) {
					return;
				}

				StringBundler stringBundler = new StringBundler(3);

				stringBundler.append(
					"HTTP/1.0 200 OK\r\nServer: \r\nContent-length: ");
				stringBundler.append(_RESPONSE_MESSAGE.length);
				stringBundler.append("\r\nContent-type: text/plain\r\n\r\n");

				OutputStream outputStream = connection.getOutputStream();

				outputStream.write(stringBundler.toString().getBytes());
				outputStream.write(_RESPONSE_MESSAGE);

				connection.shutdownOutput();
			}
			catch (IOException ioe) {
				throw new RuntimeException(ioe);
			}
			finally {
				if (connection != null) {
					try {
						connection.close();
					}
					catch (IOException ioe) {
					}
				}
			}
		}

		private ServerSocket _serverSocket;

	}

}