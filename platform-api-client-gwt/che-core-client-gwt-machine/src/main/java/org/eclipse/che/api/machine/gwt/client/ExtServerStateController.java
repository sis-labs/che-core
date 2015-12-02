/*******************************************************************************
 * Copyright (c) 2012-2015 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.api.machine.gwt.client;

import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.machine.gwt.client.events.ExtServerStateEvent;
import org.eclipse.che.api.promises.client.Promise;
import org.eclipse.che.api.promises.client.callback.AsyncPromiseHelper;
import org.eclipse.che.ide.ui.loaders.initializationLoader.InitialLoadingInfo;
import org.eclipse.che.ide.ui.loaders.initializationLoader.LoaderPresenter;
import org.eclipse.che.ide.util.loging.Log;
import org.eclipse.che.ide.websocket.MessageBus;
import org.eclipse.che.ide.websocket.MessageBusProvider;
import org.eclipse.che.ide.websocket.WebSocket;
import org.eclipse.che.ide.websocket.events.ConnectionClosedHandler;
import org.eclipse.che.ide.websocket.events.ConnectionErrorHandler;
import org.eclipse.che.ide.websocket.events.ConnectionOpenedHandler;
import org.eclipse.che.ide.websocket.events.WebSocketClosedEvent;

import static org.eclipse.che.ide.ui.loaders.initializationLoader.InitialLoadingInfo.Operations.EXTENSION_SERVER_BOOTING;
import static org.eclipse.che.ide.ui.loaders.initializationLoader.OperationInfo.Status.ERROR;
import static org.eclipse.che.ide.ui.loaders.initializationLoader.OperationInfo.Status.IN_PROGRESS;
import static org.eclipse.che.ide.ui.loaders.initializationLoader.OperationInfo.Status.SUCCESS;

/**
 * @author Roman Nikitenko
 * @author Valeriy Svydenko
 */
@Singleton
public class ExtServerStateController implements ConnectionOpenedHandler, ConnectionClosedHandler, ConnectionErrorHandler {

    private final Timer              retryConnectionTimer;
    private final EventBus           eventBus;
    private final MessageBusProvider messageBusProvider;
    private final InitialLoadingInfo initialLoadingInfo;
    private final LoaderPresenter    loader;

    private MessageBus                messageBus;
    private ExtServerState            state;
    private String                    wsUrl;
    private int                       countRetry;
    private AsyncCallback<MessageBus> messageBusCallback;

    @Inject
    public ExtServerStateController(EventBus eventBus,
                                    LoaderPresenter loader,
                                    MessageBusProvider messageBusProvider,
                                    InitialLoadingInfo initialLoadingInfo) {
        this.loader = loader;
        this.eventBus = eventBus;
        this.messageBusProvider = messageBusProvider;
        this.initialLoadingInfo = initialLoadingInfo;

        retryConnectionTimer = new Timer() {
            @Override
            public void run() {
                connect();
                countRetry--;
            }
        };
    }

    public void initialize(String wsUrl) {
        this.wsUrl = wsUrl;
        this.countRetry = 50;
        this.state = ExtServerState.STOPPED;

        initialLoadingInfo.setOperationStatus(EXTENSION_SERVER_BOOTING.getValue(), IN_PROGRESS);
        connect();
    }

    @Override
    public void onClose(WebSocketClosedEvent event) {
        if (state == ExtServerState.STARTED) {
            Log.debug(getClass(), "WebSocket connection to Extension Server closed going to reconnect");
            countRetry = 2; //try to reconnect few times in case some problem with network and ext server still running
            retryConnectionTimer.schedule(1000);
        }
    }

    @Override
    public void onError() {
        if (countRetry > 0) {
            retryConnectionTimer.schedule(1000);
        } else {
            state = ExtServerState.STOPPED;
            initialLoadingInfo.setOperationStatus(EXTENSION_SERVER_BOOTING.getValue(), ERROR);
            loader.hide();
            eventBus.fireEvent(ExtServerStateEvent.createExtServerStoppedEvent());
        }
    }

    @Override
    public void onOpen() {
        state = ExtServerState.STARTED;
        initialLoadingInfo.setOperationStatus(EXTENSION_SERVER_BOOTING.getValue(), SUCCESS);
        loader.hide();

        messageBus = messageBusProvider.createMachineMessageBus(wsUrl);
        eventBus.fireEvent(ExtServerStateEvent.createExtServerStartedEvent());

        if (messageBusCallback != null) {
            messageBusCallback.onSuccess(messageBus);
        }
    }

    public ExtServerState getState() {
        return state;
    }

    public Promise<MessageBus> getMessageBus() {
        return AsyncPromiseHelper.createFromAsyncRequest(new AsyncPromiseHelper.RequestCall<MessageBus>() {
            @Override
            public void makeCall(AsyncCallback<MessageBus> callback) {
                if (messageBus != null) {
                    callback.onSuccess(messageBus);
                } else {
                    ExtServerStateController.this.messageBusCallback = callback;
                }
            }
        });
    }

    private void connect() {
        WebSocket socket = WebSocket.create(wsUrl);
        socket.setOnOpenHandler(this);
        socket.setOnCloseHandler(this);
        socket.setOnErrorHandler(this);
    }
}
