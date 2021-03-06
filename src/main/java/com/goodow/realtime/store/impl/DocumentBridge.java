/*
 * Copyright 2012 Goodow.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.goodow.realtime.store.impl;

import com.goodow.realtime.core.Handler;
import com.goodow.realtime.core.Platform;
import com.goodow.realtime.json.Json;
import com.goodow.realtime.json.JsonArray;
import com.goodow.realtime.json.JsonArray.ListIterator;
import com.goodow.realtime.json.JsonElement;
import com.goodow.realtime.json.JsonObject;
import com.goodow.realtime.json.JsonObject.MapIterator;
import com.goodow.realtime.operation.OperationComponent;
import com.goodow.realtime.operation.OperationSink;
import com.goodow.realtime.operation.create.CreateComponent;
import com.goodow.realtime.operation.impl.AbstractComponent;
import com.goodow.realtime.operation.impl.CollaborativeOperation;
import com.goodow.realtime.operation.impl.CollaborativeTransformer;
import com.goodow.realtime.operation.undo.UndoManager;
import com.goodow.realtime.operation.undo.UndoManagerFactory;
import com.goodow.realtime.store.Collaborator;
import com.goodow.realtime.store.Document;
import com.goodow.realtime.store.Error;
import com.goodow.realtime.store.EventType;
import com.goodow.realtime.store.Store;
import com.goodow.realtime.store.UndoRedoStateChangedEvent;
import com.goodow.realtime.store.channel.Constants;
import com.goodow.realtime.store.channel.Constants.Key;

/**
 * Internal utilities for the Realtime API.
 */
public class DocumentBridge implements OperationSink<CollaborativeOperation> {
  public interface OutputSink extends OperationSink<CollaborativeOperation> {
    OutputSink VOID = new OutputSink() {
      @Override
      public void close() {
      }

      @Override
      public void consume(CollaborativeOperation op) {
      }
    };

    void close();
  }

  final Store store;
  final String id;
  private final DocumentImpl document;
  private final ModelImpl model;
  private UndoManager<CollaborativeOperation> undoManager = UndoManagerFactory.getNoOp();
  OutputSink outputSink = OutputSink.VOID;

  public DocumentBridge(final Store store, String id, JsonArray components, JsonArray collaborators,
      final Handler<Error> errorHandler) {
    this.store = store == null ? new MemoryStore() : store;
    this.id = id;
    document = new DocumentImpl(this, errorHandler);
    model = document.getModel();

    collaborators = collaborators == null ? Json.createArray() : collaborators;
    collaborators.forEach(new ListIterator<JsonObject>() {
      @Override
      public void call(int index, JsonObject obj) {
        boolean isMe = store.getBus().getSessionId().equals(obj.getString("sessionId"));
        Collaborator collaborator = new CollaboratorImpl(obj.set(Key.IS_ME, isMe));
        document.collaborators.set(collaborator.sessionId(), collaborator);
      }
    });

    if (components != null && components.length() > 0) {
      final CollaborativeTransformer transformer = new CollaborativeTransformer();
      CollaborativeOperation operation =
          transformer.createOperation(Json.createObject().set("op", components));
      applyLocally(operation);
    }
  }

  /*
   * Incoming operations from remote
   */
  @Override
  public void consume(CollaborativeOperation operation) {
    applyLocally(operation);
    undoManager.nonUndoableOp(operation);
  }

  public void createRoot() {
    model.createRoot();
  }

  public Document getDocument() {
    return document;
  }

  public <T> void scheduleHandle(final Handler<T> handler, final T event) {
    Platform.scheduler().scheduleDeferred(new Handler<Void>() {
      @Override
      public void handle(Void ignore) {
        Platform.scheduler().handle(handler, event);
      }
    });
  }

  public void setOutputSink(OutputSink outputSink) {
    this.outputSink = outputSink;
  }

  public void setUndoEnabled(boolean undoEnabled) {
    undoManager =
        undoEnabled ? UndoManagerFactory.createUndoManager() : UndoManagerFactory
            .<CollaborativeOperation> getNoOp();
  }

  public JsonObject toJson() {
    return model.getRoot().toJson();
  }

  public JsonArray toSnapshot() {
    final JsonArray createComponents = Json.createArray();
    final JsonArray components = Json.createArray();
    model.objects.forEach(new MapIterator<CollaborativeObjectImpl>() {
      @Override
      public void call(String key, CollaborativeObjectImpl object) {
        OperationComponent<?>[] initializeComponents = object.toInitialization();
        boolean isCreateOp = true;
        for (OperationComponent<?> component : initializeComponents) {
          if (isCreateOp) {
            createComponents.push(component.toJson());
            isCreateOp = false;
          } else {
            components.push(component.toJson());
          }
        }
      }
    });
    components.forEach(new ListIterator<JsonElement>() {
      @Override
      public void call(int index, JsonElement component) {
        createComponents.push(component);
      }
    });
    return createComponents;
  }

  @Override
  public String toString() {
    return toJson().toJsonString();
  }

  void consumeAndSubmit(OperationComponent<?> component) {
    Collaborator me = document.collaborators.get(store.getBus().getSessionId());
    CollaborativeOperation operation =
        new CollaborativeOperation(me == null ? null : me.userId(), store.getBus().getSessionId(),
                                   Json.createArray().push(component));
    applyLocally(operation);
    undoManager.checkpoint();
    undoManager.undoableOp(operation);
    mayUndoRedoStateChanged();
    outputSink.consume(operation);
  }

  boolean isLocalSession(String sessionId) {
    return store.getBus().getSessionId().equals(sessionId);
  }

  void redo() {
    bypassUndoStack(undoManager.redo());
  }

  void undo() {
    bypassUndoStack(undoManager.undo());
  }

  private void applyLocally(final CollaborativeOperation operation) {
    operation.components.forEach(new ListIterator<AbstractComponent<?>>() {
      @Override
      public void call(int index, AbstractComponent<?> component) {
        if (component.type != CreateComponent.TYPE) {
          model.<CollaborativeObjectImpl>getObject(component.id).consume(operation.userId, operation.sessionId, component);
          return;
        }
        CollaborativeObjectImpl obj;
        switch (((CreateComponent) component).subType) {
          case CreateComponent.MAP:
            obj = new CollaborativeMapImpl(model);
            break;
          case CreateComponent.LIST:
            obj = new CollaborativeListImpl(model);
            break;
          case CreateComponent.STRING:
            obj = new CollaborativeStringImpl(model);
            break;
          case CreateComponent.INDEX_REFERENCE:
            obj = new IndexReferenceImpl(model);
            break;
          default:
            throw new RuntimeException("Shouldn't reach here!");
        }
        obj.id = component.id;
        model.objects.set(obj.id, obj);
        model.bytesUsed += component.toString().length();
        model.bytesUsed++;
      }
    });
  }

  /**
   * Applies an op locally and send it bypassing the undo stack. This is necessary with operations
   * popped from the undoManager as they are automatically applied.
   * 
   * @param operation
   */
  private void bypassUndoStack(CollaborativeOperation operation) {
    applyLocally(operation);
    outputSink.consume(operation);
    mayUndoRedoStateChanged();
  }

  private void mayUndoRedoStateChanged() {
    boolean canUndo = undoManager.canUndo();
    boolean canRedo = undoManager.canRedo();
    if (model.canUndo() != canUndo || model.canRedo() != canRedo) {
      model.canUndo = canUndo;
      model.canRedo = canRedo;
      UndoRedoStateChangedEvent event =
          new UndoRedoStateChangedEventImpl(model, Json.createObject().set("canUndo", canUndo)
              .set("canRedo", canRedo));
      store.getBus().publishLocal(Constants.Topic.STORE + "/" + id + "/" + EventType.UNDO_REDO_STATE_CHANGED,
                                  event);
    }
  }
}