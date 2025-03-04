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

package org.apache.kyuubi.operation

import scala.collection.JavaConverters._

import org.apache.hive.service.rpc.thrift.{TCLIService, TCloseSessionReq, TOpenSessionReq, TSessionHandle}
import org.apache.hive.service.rpc.thrift.TCLIService.Iface
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TSocket

import org.apache.kyuubi.{Logging, Utils}
import org.apache.kyuubi.service.FrontendService
import org.apache.kyuubi.service.authentication.PlainSASLHelper

object TClientTestUtils extends Logging {

  def withThriftClient[T](url: String)(f: Iface => T): T = {
    val hostport = url.split(':')
    val socket = new TSocket(hostport.head, hostport.last.toInt)
    val transport = PlainSASLHelper.getPlainTransport(Utils.currentUser, "anonymous", socket)
    val protocol = new TBinaryProtocol(transport)
    val client = new TCLIService.Client(protocol)
    transport.open()
    try {
      f(client)
    } finally {
      socket.close()
    }
  }

  /**
   * s shall be [[TFrontendService]]
   */
  def withThriftClient[T](s: FrontendService)(f: Iface => T): T = {
    withThriftClient(s.connectionUrl)(f)
  }

  def withSessionHandle[T](url: String, configs: Map[String, String])(
      f: (TCLIService.Iface, TSessionHandle) => T): T = {
    withThriftClient(url) { client =>
      val req = new TOpenSessionReq()
      req.setUsername(Utils.currentUser)
      req.setPassword("anonymous")
      req.setConfiguration(configs.asJava)
      val resp = client.OpenSession(req)
      val handle = resp.getSessionHandle
      try {
        f(client, handle)
      } finally {
        val tCloseSessionReq = new TCloseSessionReq(handle)
        try {
          client.CloseSession(tCloseSessionReq)
        } catch {
          case e: Exception => error(s"Failed to close $handle", e)
        }
      }
    }
  }
}
