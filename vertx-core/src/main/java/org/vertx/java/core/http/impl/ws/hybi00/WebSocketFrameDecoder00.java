/*
 * Copyright 2008-2011 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * Modified from original form by Tim Fox
 */

package org.vertx.java.core.http.impl.ws.hybi00;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.replay.ReplayingDecoder;
import org.jboss.netty.handler.codec.replay.VoidEnum;
import org.vertx.java.core.http.impl.ws.DefaultWebSocketFrame;
import org.vertx.java.core.http.impl.ws.WebSocketFrame;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import static org.vertx.java.core.http.impl.ws.WebSocketFrame.FrameType;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class WebSocketFrameDecoder00 extends ReplayingDecoder<VoidEnum> {

  private static final Logger log = LoggerFactory.getLogger(WebSocketFrameDecoder00.class);

  public static final int DEFAULT_MAX_FRAME_SIZE = 128 * 1024;

  private final int maxFrameSize;

  WebSocketFrameDecoder00() {
    this(DEFAULT_MAX_FRAME_SIZE);
  }

  /**
   * Creates a new instance of {@code WebSocketFrameDecoder} with the specified {@code maxFrameSize}.  If the client
   * sends a frame size larger than {@code maxFrameSize}, the channel will be closed.
   *
   * @param maxFrameSize the maximum frame size to decode
   */
  WebSocketFrameDecoder00(int maxFrameSize) {
    this.maxFrameSize = maxFrameSize;
  }

  @Override
  protected Object decode(ChannelHandlerContext ctx, Channel channel,
                          ChannelBuffer buffer, VoidEnum state) throws Exception {
    byte type = buffer.readByte();

    if ((type & 0x80) == 0x80) {
      // If the MSB on type is set, decode the frame length
      return decodeBinaryFrame(type, buffer);
    } else {
      // Decode a 0xff terminated UTF-8 string
      return decodeTextFrame(type, buffer);
    }
  }

  private WebSocketFrame decodeBinaryFrame(int type, ChannelBuffer buffer) throws TooLongFrameException {
    long frameSize = 0;
    int lengthFieldSize = 0;
    byte b;
    do {
      b = buffer.readByte();
      frameSize <<= 7;
      frameSize |= b & 0x7f;
      if (frameSize > maxFrameSize) {
        throw new TooLongFrameException();
      }
      lengthFieldSize++;
      if (lengthFieldSize > 8) {
        // Perhaps a malicious peer?
        throw new TooLongFrameException();
      }
    } while ((b & 0x80) == 0x80);

    if (frameSize == 0 && type == -1) {
      return new DefaultWebSocketFrame(FrameType.CLOSE);
    }

    return new DefaultWebSocketFrame(FrameType.BINARY, buffer.readBytes((int) frameSize));
  }

  private WebSocketFrame decodeTextFrame(int type, ChannelBuffer buffer) throws TooLongFrameException {
    int ridx = buffer.readerIndex();
    int rbytes = actualReadableBytes();
    int delimPos = buffer.indexOf(ridx, ridx + rbytes, (byte) 0xFF);
    if (delimPos == -1) {
      // Frame delimiter (0xFF) not found
      if (rbytes > maxFrameSize) {
        // Frame length exceeded the maximum
        throw new TooLongFrameException();
      } else {
        // Wait until more data is received
        return null;
      }
    }

    int frameSize = delimPos - ridx;
    if (frameSize > maxFrameSize) {
      throw new TooLongFrameException();
    }

    ChannelBuffer binaryData = buffer.readBytes(frameSize);
    buffer.skipBytes(1);
    DefaultWebSocketFrame frame = new DefaultWebSocketFrame(FrameType.TEXT, binaryData);
    return frame;
  }
}
