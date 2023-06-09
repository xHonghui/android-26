/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import static android.system.OsConstants.POLLIN;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.Os;
import android.system.ErrnoException;
import android.system.StructPollfd;
import android.util.Log;

import java.io.IOException;
import java.io.FileDescriptor;
import java.util.ArrayList;

/**
 * Server socket class for zygote processes.
 *
 * Provides functions to wait for commands on a UNIX domain socket, and fork
 * off child processes that inherit the initial state of the VM.%
 *
 * Please see {@link ZygoteConnection.Arguments} for documentation on the
 * client protocol.
 */
class ZygoteServer {
    public static final String TAG = "ZygoteServer";

    private static final String ANDROID_SOCKET_PREFIX = "ANDROID_SOCKET_";

    private LocalServerSocket mServerSocket;

    ZygoteServer() {
    }

    /**
     * Registers a server socket for zygote command connections
     *
     * @throws RuntimeException when open fails
     */
    void registerServerSocket(String socketName) {
        if (mServerSocket == null) {
            int fileDesc;
            final String fullSocketName = ANDROID_SOCKET_PREFIX + socketName;
            try {
                String env = System.getenv(fullSocketName);
                fileDesc = Integer.parseInt(env);
            } catch (RuntimeException ex) {
                throw new RuntimeException(fullSocketName + " unset or invalid", ex);
            }

            try {
                FileDescriptor fd = new FileDescriptor();
                fd.setInt$(fileDesc);
                mServerSocket = new LocalServerSocket(fd);
            } catch (IOException ex) {
                throw new RuntimeException(
                        "Error binding to local socket '" + fileDesc + "'", ex);
            }
        }
    }

    /**
     * Waits for and accepts a single command connection. Throws
     * RuntimeException on failure.
     * 等待并接受单个命令连接
     */
    private ZygoteConnection acceptCommandPeer(String abiList) {
        try {
            //创建新链接
            return createNewConnection(mServerSocket.accept(), abiList);
        } catch (IOException ex) {
            throw new RuntimeException(
                    "IOException during accept()", ex);
        }
    }

    protected ZygoteConnection createNewConnection(LocalSocket socket, String abiList)
            throws IOException {
        return new ZygoteConnection(socket, abiList);
    }

    /**
     * Close and clean up zygote sockets. Called on shutdown and on the
     * child's exit path.
     * 关闭并清理 zygote 套接字。在关机和孩子的退出路径上调用。
     */
    void closeServerSocket() {
        try {
            if (mServerSocket != null) {
                FileDescriptor fd = mServerSocket.getFileDescriptor();
                mServerSocket.close();
                if (fd != null) {
                    Os.close(fd);
                }
            }
        } catch (IOException ex) {
            Log.e(TAG, "Zygote:  error closing sockets", ex);
        } catch (ErrnoException ex) {
            Log.e(TAG, "Zygote:  error closing descriptor", ex);
        }

        mServerSocket = null;
    }

    /**
     * Return the server socket's underlying file descriptor, so that
     * ZygoteConnection can pass it to the native code for proper
     * closure after a child process is forked off.
     */

    FileDescriptor getServerSocketFileDescriptor() {
        return mServerSocket.getFileDescriptor();
    }

    /**
     * Runs the zygote process's select loop. Accepts new connections as
     * they happen, and reads commands from connections one spawn-request's
     * worth at a time.
     * 运行 zygote 进程的选择循环。在新连接发生时接受它们，并一次从连接中读取一个生成请求的值的命令。
     *
     * @throws Zygote.MethodAndArgsCaller in a child process when a main()
     * should be executed.
     * 抛出： Zygote.MethodAndArgsCaller – 在应该执行 main() 的子进程中。
     */
    void runSelectLoop(String abiList) throws Zygote.MethodAndArgsCaller {
        ArrayList<FileDescriptor> fds = new ArrayList<FileDescriptor>();
        ArrayList<ZygoteConnection> peers = new ArrayList<ZygoteConnection>();

        fds.add(mServerSocket.getFileDescriptor());
        peers.add(null);

        while (true) {
            StructPollfd[] pollFds = new StructPollfd[fds.size()];
            for (int i = 0; i < pollFds.length; ++i) {
                pollFds[i] = new StructPollfd();
                pollFds[i].fd = fds.get(i);
                pollFds[i].events = (short) POLLIN;
            }
            try {
                Os.poll(pollFds, -1);
            } catch (ErrnoException ex) {
                throw new RuntimeException("poll failed", ex);
            }
            for (int i = pollFds.length - 1; i >= 0; --i) {
                if ((pollFds[i].revents & POLLIN) == 0) {
                    continue;
                }
                if (i == 0) {//i==0，说明服务端与客户端连接上，也就是当前Zygote进程与AMS进程连接上
                    //todo 监听 socket 请求，返回一个 ZygoteConnection 对象
                    ZygoteConnection newPeer = acceptCommandPeer(abiList);
                    peers.add(newPeer);
                    fds.add(newPeer.getFileDesciptor());
                } else {
                    //todo 不断处理客户端（AMS）发送过来的请求
                    boolean done = peers.get(i).runOnce(this);
                    if (done) {
                        //处理完成，移除
                        peers.remove(i);
                        fds.remove(i);
                    }
                }
            }
        }
    }
}
