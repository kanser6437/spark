/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.connect

import java.util.UUID

import org.scalatest.concurrent.{Eventually, TimeLimits}
import org.scalatest.time.Span
import org.scalatest.time.SpanSugar._

import org.apache.spark.connect.proto
import org.apache.spark.sql.connect.client.{CloseableIterator, CustomSparkConnectBlockingStub, ExecutePlanResponseReattachableIterator, GrpcRetryHandler, SparkConnectClient, WrappedCloseableIterator}
import org.apache.spark.sql.connect.common.config.ConnectCommon
import org.apache.spark.sql.connect.config.Connect
import org.apache.spark.sql.connect.dsl.MockRemoteSession
import org.apache.spark.sql.connect.dsl.plans._
import org.apache.spark.sql.connect.service.{ExecuteHolder, SparkConnectService}
import org.apache.spark.sql.test.SharedSparkSession

/**
 * Base class and utilities for a test suite that starts and tests the real SparkConnectService
 * with a real SparkConnectClient, communicating over RPC, but both in-process.
 */
class SparkConnectServerTest extends SharedSparkSession {

  // Server port
  val serverPort: Int =
    ConnectCommon.CONNECT_GRPC_BINDING_PORT + util.Random.nextInt(1000)

  val eventuallyTimeout = 30.seconds

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Other suites using mocks leave a mess in the global executionManager,
    // shut it down so that it's cleared before starting server.
    SparkConnectService.executionManager.shutdown()
    // Start the real service.
    withSparkEnvConfs((Connect.CONNECT_GRPC_BINDING_PORT.key, serverPort.toString)) {
      SparkConnectService.start(spark.sparkContext)
    }
    // register udf directly on the server, we're not testing client UDFs here...
    val serverSession =
      SparkConnectService.getOrCreateIsolatedSession(defaultUserId, defaultSessionId).session
    serverSession.udf.register("sleep", ((ms: Int) => { Thread.sleep(ms); ms }))
  }

  override def afterAll(): Unit = {
    SparkConnectService.stop()
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    clearAllExecutions()
  }

  override def afterEach(): Unit = {
    clearAllExecutions()
    super.afterEach()
  }

  protected def clearAllExecutions(): Unit = {
    SparkConnectService.executionManager.listExecuteHolders.foreach(_.close())
    SparkConnectService.executionManager.periodicMaintenance(0)
    assertNoActiveExecutions()
  }

  protected val defaultSessionId = UUID.randomUUID.toString()
  protected val defaultUserId = UUID.randomUUID.toString()

  // We don't have the real SparkSession/Dataset api available,
  // so use mock for generating simple query plans.
  protected val dsl = new MockRemoteSession()

  protected val userContext = proto.UserContext
    .newBuilder()
    .setUserId(defaultUserId)
    .build()

  protected def buildExecutePlanRequest(
      plan: proto.Plan,
      sessionId: String = defaultSessionId,
      operationId: String = UUID.randomUUID.toString) = {
    proto.ExecutePlanRequest
      .newBuilder()
      .setUserContext(userContext)
      .setSessionId(sessionId)
      .setOperationId(operationId)
      .setPlan(plan)
      .addRequestOptions(
        proto.ExecutePlanRequest.RequestOption
          .newBuilder()
          .setReattachOptions(proto.ReattachOptions.newBuilder().setReattachable(true).build())
          .build())
      .build()
  }

  protected def buildReattachExecuteRequest(operationId: String, responseId: Option[String]) = {
    val req = proto.ReattachExecuteRequest
      .newBuilder()
      .setUserContext(userContext)
      .setSessionId(defaultSessionId)
      .setOperationId(operationId)

    if (responseId.isDefined) {
      req.setLastResponseId(responseId.get)
    }

    req.build()
  }

  protected def buildPlan(query: String) = {
    proto.Plan.newBuilder().setRoot(dsl.sql(query)).build()
  }

  protected def getReattachableIterator(
      stubIterator: CloseableIterator[proto.ExecutePlanResponse]) = {
    // This depends on the wrapping in CustomSparkConnectBlockingStub.executePlanReattachable:
    // GrpcExceptionConverter.convertIterator
    stubIterator
      .asInstanceOf[WrappedCloseableIterator[proto.ExecutePlanResponse]]
      // ExecutePlanResponseReattachableIterator
      .innerIterator
      .asInstanceOf[ExecutePlanResponseReattachableIterator]
  }

  protected def assertNoActiveRpcs(): Unit = {
    SparkConnectService.executionManager.listActiveExecutions match {
      case Left(_) => // nothing running, good
      case Right(executions) =>
        // all rpc detached.
        assert(
          executions.forall(_.lastAttachedRpcTime.isDefined),
          s"Expected no RPCs, but got $executions")
    }
  }

  protected def assertEventuallyNoActiveRpcs(): Unit = {
    Eventually.eventually(timeout(eventuallyTimeout)) {
      assertNoActiveRpcs()
    }
  }

  protected def assertNoActiveExecutions(): Unit = {
    SparkConnectService.executionManager.listActiveExecutions match {
      case Left(_) => // cleaned up
      case Right(executions) => fail(s"Expected empty, but got $executions")
    }
  }

  protected def assertEventuallyNoActiveExecutions(): Unit = {
    Eventually.eventually(timeout(eventuallyTimeout)) {
      assertNoActiveExecutions()
    }
  }

  protected def assertExecutionReleased(operationId: String): Unit = {
    SparkConnectService.executionManager.listActiveExecutions match {
      case Left(_) => // cleaned up
      case Right(executions) => assert(!executions.exists(_.operationId == operationId))
    }
  }

  protected def assertEventuallyExecutionReleased(operationId: String): Unit = {
    Eventually.eventually(timeout(eventuallyTimeout)) {
      assertExecutionReleased(operationId)
    }
  }

  // Get ExecutionHolder, assuming that only one execution is active
  protected def getExecutionHolder: ExecuteHolder = {
    val executions = SparkConnectService.executionManager.listExecuteHolders
    assert(executions.length == 1)
    executions.head
  }

  protected def withClient(f: SparkConnectClient => Unit): Unit = {
    val client = SparkConnectClient
      .builder()
      .port(serverPort)
      .sessionId(defaultSessionId)
      .userId(defaultUserId)
      .enableReattachableExecute()
      .build()
    try f(client)
    finally {
      client.shutdown()
    }
  }

  protected def withRawBlockingStub(
      f: proto.SparkConnectServiceGrpc.SparkConnectServiceBlockingStub => Unit): Unit = {
    val conf = SparkConnectClient.Configuration(port = serverPort)
    val channel = conf.createChannel()
    val bstub = proto.SparkConnectServiceGrpc.newBlockingStub(channel)
    try f(bstub)
    finally {
      channel.shutdownNow()
    }
  }

  protected def withCustomBlockingStub(
      retryPolicy: GrpcRetryHandler.RetryPolicy = GrpcRetryHandler.RetryPolicy())(
      f: CustomSparkConnectBlockingStub => Unit): Unit = {
    val conf = SparkConnectClient.Configuration(port = serverPort)
    val channel = conf.createChannel()
    val bstub = new CustomSparkConnectBlockingStub(channel, retryPolicy)
    try f(bstub)
    finally {
      channel.shutdownNow()
    }
  }

  protected def runQuery(plan: proto.Plan, queryTimeout: Span, iterSleep: Long): Unit = {
    withClient { client =>
      TimeLimits.failAfter(queryTimeout) {
        val iter = client.execute(plan)
        var operationId: Option[String] = None
        var r: proto.ExecutePlanResponse = null
        val reattachableIter = getReattachableIterator(iter)
        while (iter.hasNext) {
          r = iter.next()
          operationId match {
            case None => operationId = Some(r.getOperationId)
            case Some(id) => assert(r.getOperationId == id)
          }
          if (iterSleep > 0) {
            Thread.sleep(iterSleep)
          }
        }
        // Check that last response had ResultComplete indicator
        assert(r != null)
        assert(r.hasResultComplete)
        // ... that client sent ReleaseExecute based on it
        assert(reattachableIter.resultComplete)
        // ... and that the server released the execution.
        assert(operationId.isDefined)
        assertEventuallyExecutionReleased(operationId.get)
      }
    }
  }

  protected def runQuery(query: String, queryTimeout: Span, iterSleep: Long = 0): Unit = {
    val plan = buildPlan(query)
    runQuery(plan, queryTimeout, iterSleep)
  }
}
