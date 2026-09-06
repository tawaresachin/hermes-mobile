package com.hermes.mobile.data.local;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.hermes.mobile.data.model.Message;
import com.hermes.mobile.data.model.MessageRole;
import com.hermes.mobile.data.model.MessageStatus;
import java.lang.Class;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class MessageDao_Impl implements MessageDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<Message> __insertionAdapterOfMessage;

  private final SharedSQLiteStatement __preparedStmtOfUpdateMessage;

  private final SharedSQLiteStatement __preparedStmtOfUpdateMessageWithAttachment;

  private final SharedSQLiteStatement __preparedStmtOfUpdateMessageStatus;

  private final SharedSQLiteStatement __preparedStmtOfDeleteStreamingPlaceholders;

  private final SharedSQLiteStatement __preparedStmtOfUpdateReaction;

  private final SharedSQLiteStatement __preparedStmtOfDeleteSessionMessages;

  private final SharedSQLiteStatement __preparedStmtOfDeleteMessage;

  private final SharedSQLiteStatement __preparedStmtOfUpdateMessageEdit;

  private final SharedSQLiteStatement __preparedStmtOfUpdateLastStreamingMessage;

  private final SharedSQLiteStatement __preparedStmtOfFinalizeStaleStreaming;

  public MessageDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfMessage = new EntityInsertionAdapter<Message>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `messages` (`id`,`sessionId`,`role`,`content`,`timestamp`,`isStreaming`,`attachmentUrl`,`attachmentType`,`attachmentName`,`replyToText`,`reaction`,`status`,`editedAt`,`tokens`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final Message entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getSessionId());
        statement.bindString(3, __MessageRole_enumToString(entity.getRole()));
        statement.bindString(4, entity.getContent());
        statement.bindLong(5, entity.getTimestamp());
        final int _tmp = entity.isStreaming() ? 1 : 0;
        statement.bindLong(6, _tmp);
        if (entity.getAttachmentUrl() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getAttachmentUrl());
        }
        if (entity.getAttachmentType() == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.getAttachmentType());
        }
        if (entity.getAttachmentName() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getAttachmentName());
        }
        if (entity.getReplyToText() == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.getReplyToText());
        }
        if (entity.getReaction() == null) {
          statement.bindNull(11);
        } else {
          statement.bindString(11, entity.getReaction());
        }
        if (entity.getStatus() == null) {
          statement.bindNull(12);
        } else {
          statement.bindString(12, __MessageStatus_enumToString(entity.getStatus()));
        }
        statement.bindLong(13, entity.getEditedAt());
        statement.bindLong(14, entity.getTokens());
      }
    };
    this.__preparedStmtOfUpdateMessage = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE messages SET content = ?, isStreaming = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateMessageWithAttachment = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE messages SET content = ?, isStreaming = ?, attachmentUrl = ?, attachmentType = ?, attachmentName = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateMessageStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE messages SET status = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteStreamingPlaceholders = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM messages WHERE sessionId = ? AND isStreaming = 1";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateReaction = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE messages SET reaction = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteSessionMessages = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM messages WHERE sessionId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteMessage = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM messages WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateMessageEdit = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE messages SET content = ?, editedAt = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateLastStreamingMessage = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "\n"
                + "        UPDATE messages SET content = ?, isStreaming = 0 \n"
                + "        WHERE id = (\n"
                + "            SELECT id FROM messages \n"
                + "            WHERE sessionId = ? AND role = 'ASSISTANT' AND isStreaming = 1 \n"
                + "            ORDER BY timestamp DESC LIMIT 1\n"
                + "        )\n"
                + "    ";
        return _query;
      }
    };
    this.__preparedStmtOfFinalizeStaleStreaming = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE messages SET isStreaming = 0 WHERE sessionId = ? AND isStreaming = 1";
        return _query;
      }
    };
  }

  @Override
  public Object insertMessage(final Message message, final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfMessage.insertAndReturnId(message);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateMessage(final long messageId, final String content, final boolean isStreaming,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateMessage.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, content);
        _argIndex = 2;
        final int _tmp = isStreaming ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, messageId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateMessage.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateMessageWithAttachment(final long messageId, final String content,
      final boolean isStreaming, final String attachmentUrl, final String attachmentType,
      final String attachmentName, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateMessageWithAttachment.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, content);
        _argIndex = 2;
        final int _tmp = isStreaming ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 3;
        _stmt.bindString(_argIndex, attachmentUrl);
        _argIndex = 4;
        _stmt.bindString(_argIndex, attachmentType);
        _argIndex = 5;
        if (attachmentName == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindString(_argIndex, attachmentName);
        }
        _argIndex = 6;
        _stmt.bindLong(_argIndex, messageId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateMessageWithAttachment.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateMessageStatus(final long messageId, final MessageStatus status,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateMessageStatus.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, __MessageStatus_enumToString(status));
        _argIndex = 2;
        _stmt.bindLong(_argIndex, messageId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateMessageStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteStreamingPlaceholders(final String sessionId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteStreamingPlaceholders.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, sessionId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteStreamingPlaceholders.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateReaction(final long messageId, final String reaction,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateReaction.acquire();
        int _argIndex = 1;
        if (reaction == null) {
          _stmt.bindNull(_argIndex);
        } else {
          _stmt.bindString(_argIndex, reaction);
        }
        _argIndex = 2;
        _stmt.bindLong(_argIndex, messageId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateReaction.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteSessionMessages(final String sessionId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteSessionMessages.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, sessionId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteSessionMessages.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteMessage(final long messageId, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteMessage.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, messageId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteMessage.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateMessageEdit(final long messageId, final String content, final long editedAt,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateMessageEdit.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, content);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, editedAt);
        _argIndex = 3;
        _stmt.bindLong(_argIndex, messageId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateMessageEdit.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateLastStreamingMessage(final String sessionId, final String content,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateLastStreamingMessage.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, content);
        _argIndex = 2;
        _stmt.bindString(_argIndex, sessionId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateLastStreamingMessage.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object finalizeStaleStreaming(final String sessionId,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfFinalizeStaleStreaming.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, sessionId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfFinalizeStaleStreaming.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<Message>> getMessages(final String sessionId) {
    final String _sql = "SELECT * FROM messages WHERE sessionId = ? ORDER BY timestamp ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, sessionId);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"messages"}, new Callable<List<Message>>() {
      @Override
      @NonNull
      public List<Message> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfIsStreaming = CursorUtil.getColumnIndexOrThrow(_cursor, "isStreaming");
          final int _cursorIndexOfAttachmentUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentUrl");
          final int _cursorIndexOfAttachmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentType");
          final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentName");
          final int _cursorIndexOfReplyToText = CursorUtil.getColumnIndexOrThrow(_cursor, "replyToText");
          final int _cursorIndexOfReaction = CursorUtil.getColumnIndexOrThrow(_cursor, "reaction");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfEditedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "editedAt");
          final int _cursorIndexOfTokens = CursorUtil.getColumnIndexOrThrow(_cursor, "tokens");
          final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Message _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            final MessageRole _tmpRole;
            _tmpRole = __MessageRole_stringToEnum(_cursor.getString(_cursorIndexOfRole));
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final boolean _tmpIsStreaming;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsStreaming);
            _tmpIsStreaming = _tmp != 0;
            final String _tmpAttachmentUrl;
            if (_cursor.isNull(_cursorIndexOfAttachmentUrl)) {
              _tmpAttachmentUrl = null;
            } else {
              _tmpAttachmentUrl = _cursor.getString(_cursorIndexOfAttachmentUrl);
            }
            final String _tmpAttachmentType;
            if (_cursor.isNull(_cursorIndexOfAttachmentType)) {
              _tmpAttachmentType = null;
            } else {
              _tmpAttachmentType = _cursor.getString(_cursorIndexOfAttachmentType);
            }
            final String _tmpAttachmentName;
            if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
              _tmpAttachmentName = null;
            } else {
              _tmpAttachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
            }
            final String _tmpReplyToText;
            if (_cursor.isNull(_cursorIndexOfReplyToText)) {
              _tmpReplyToText = null;
            } else {
              _tmpReplyToText = _cursor.getString(_cursorIndexOfReplyToText);
            }
            final String _tmpReaction;
            if (_cursor.isNull(_cursorIndexOfReaction)) {
              _tmpReaction = null;
            } else {
              _tmpReaction = _cursor.getString(_cursorIndexOfReaction);
            }
            final MessageStatus _tmpStatus;
            if (_cursor.isNull(_cursorIndexOfStatus)) {
              _tmpStatus = null;
            } else {
              _tmpStatus = __MessageStatus_stringToEnum(_cursor.getString(_cursorIndexOfStatus));
            }
            final long _tmpEditedAt;
            _tmpEditedAt = _cursor.getLong(_cursorIndexOfEditedAt);
            final long _tmpTokens;
            _tmpTokens = _cursor.getLong(_cursorIndexOfTokens);
            _item = new Message(_tmpId,_tmpSessionId,_tmpRole,_tmpContent,_tmpTimestamp,_tmpIsStreaming,_tmpAttachmentUrl,_tmpAttachmentType,_tmpAttachmentName,_tmpReplyToText,_tmpReaction,_tmpStatus,_tmpEditedAt,_tmpTokens);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getMessagesOnce(final String sessionId,
      final Continuation<? super List<Message>> $completion) {
    final String _sql = "SELECT * FROM messages WHERE sessionId = ? ORDER BY timestamp ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, sessionId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<Message>>() {
      @Override
      @NonNull
      public List<Message> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfIsStreaming = CursorUtil.getColumnIndexOrThrow(_cursor, "isStreaming");
          final int _cursorIndexOfAttachmentUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentUrl");
          final int _cursorIndexOfAttachmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentType");
          final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentName");
          final int _cursorIndexOfReplyToText = CursorUtil.getColumnIndexOrThrow(_cursor, "replyToText");
          final int _cursorIndexOfReaction = CursorUtil.getColumnIndexOrThrow(_cursor, "reaction");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfEditedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "editedAt");
          final int _cursorIndexOfTokens = CursorUtil.getColumnIndexOrThrow(_cursor, "tokens");
          final List<Message> _result = new ArrayList<Message>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final Message _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            final MessageRole _tmpRole;
            _tmpRole = __MessageRole_stringToEnum(_cursor.getString(_cursorIndexOfRole));
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final boolean _tmpIsStreaming;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsStreaming);
            _tmpIsStreaming = _tmp != 0;
            final String _tmpAttachmentUrl;
            if (_cursor.isNull(_cursorIndexOfAttachmentUrl)) {
              _tmpAttachmentUrl = null;
            } else {
              _tmpAttachmentUrl = _cursor.getString(_cursorIndexOfAttachmentUrl);
            }
            final String _tmpAttachmentType;
            if (_cursor.isNull(_cursorIndexOfAttachmentType)) {
              _tmpAttachmentType = null;
            } else {
              _tmpAttachmentType = _cursor.getString(_cursorIndexOfAttachmentType);
            }
            final String _tmpAttachmentName;
            if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
              _tmpAttachmentName = null;
            } else {
              _tmpAttachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
            }
            final String _tmpReplyToText;
            if (_cursor.isNull(_cursorIndexOfReplyToText)) {
              _tmpReplyToText = null;
            } else {
              _tmpReplyToText = _cursor.getString(_cursorIndexOfReplyToText);
            }
            final String _tmpReaction;
            if (_cursor.isNull(_cursorIndexOfReaction)) {
              _tmpReaction = null;
            } else {
              _tmpReaction = _cursor.getString(_cursorIndexOfReaction);
            }
            final MessageStatus _tmpStatus;
            if (_cursor.isNull(_cursorIndexOfStatus)) {
              _tmpStatus = null;
            } else {
              _tmpStatus = __MessageStatus_stringToEnum(_cursor.getString(_cursorIndexOfStatus));
            }
            final long _tmpEditedAt;
            _tmpEditedAt = _cursor.getLong(_cursorIndexOfEditedAt);
            final long _tmpTokens;
            _tmpTokens = _cursor.getLong(_cursorIndexOfTokens);
            _item = new Message(_tmpId,_tmpSessionId,_tmpRole,_tmpContent,_tmpTimestamp,_tmpIsStreaming,_tmpAttachmentUrl,_tmpAttachmentType,_tmpAttachmentName,_tmpReplyToText,_tmpReaction,_tmpStatus,_tmpEditedAt,_tmpTokens);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getMessage(final long messageId, final Continuation<? super Message> $completion) {
    final String _sql = "SELECT * FROM messages WHERE id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, messageId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Message>() {
      @Override
      @Nullable
      public Message call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfRole = CursorUtil.getColumnIndexOrThrow(_cursor, "role");
          final int _cursorIndexOfContent = CursorUtil.getColumnIndexOrThrow(_cursor, "content");
          final int _cursorIndexOfTimestamp = CursorUtil.getColumnIndexOrThrow(_cursor, "timestamp");
          final int _cursorIndexOfIsStreaming = CursorUtil.getColumnIndexOrThrow(_cursor, "isStreaming");
          final int _cursorIndexOfAttachmentUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentUrl");
          final int _cursorIndexOfAttachmentType = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentType");
          final int _cursorIndexOfAttachmentName = CursorUtil.getColumnIndexOrThrow(_cursor, "attachmentName");
          final int _cursorIndexOfReplyToText = CursorUtil.getColumnIndexOrThrow(_cursor, "replyToText");
          final int _cursorIndexOfReaction = CursorUtil.getColumnIndexOrThrow(_cursor, "reaction");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfEditedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "editedAt");
          final int _cursorIndexOfTokens = CursorUtil.getColumnIndexOrThrow(_cursor, "tokens");
          final Message _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpSessionId;
            _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            final MessageRole _tmpRole;
            _tmpRole = __MessageRole_stringToEnum(_cursor.getString(_cursorIndexOfRole));
            final String _tmpContent;
            _tmpContent = _cursor.getString(_cursorIndexOfContent);
            final long _tmpTimestamp;
            _tmpTimestamp = _cursor.getLong(_cursorIndexOfTimestamp);
            final boolean _tmpIsStreaming;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIsStreaming);
            _tmpIsStreaming = _tmp != 0;
            final String _tmpAttachmentUrl;
            if (_cursor.isNull(_cursorIndexOfAttachmentUrl)) {
              _tmpAttachmentUrl = null;
            } else {
              _tmpAttachmentUrl = _cursor.getString(_cursorIndexOfAttachmentUrl);
            }
            final String _tmpAttachmentType;
            if (_cursor.isNull(_cursorIndexOfAttachmentType)) {
              _tmpAttachmentType = null;
            } else {
              _tmpAttachmentType = _cursor.getString(_cursorIndexOfAttachmentType);
            }
            final String _tmpAttachmentName;
            if (_cursor.isNull(_cursorIndexOfAttachmentName)) {
              _tmpAttachmentName = null;
            } else {
              _tmpAttachmentName = _cursor.getString(_cursorIndexOfAttachmentName);
            }
            final String _tmpReplyToText;
            if (_cursor.isNull(_cursorIndexOfReplyToText)) {
              _tmpReplyToText = null;
            } else {
              _tmpReplyToText = _cursor.getString(_cursorIndexOfReplyToText);
            }
            final String _tmpReaction;
            if (_cursor.isNull(_cursorIndexOfReaction)) {
              _tmpReaction = null;
            } else {
              _tmpReaction = _cursor.getString(_cursorIndexOfReaction);
            }
            final MessageStatus _tmpStatus;
            if (_cursor.isNull(_cursorIndexOfStatus)) {
              _tmpStatus = null;
            } else {
              _tmpStatus = __MessageStatus_stringToEnum(_cursor.getString(_cursorIndexOfStatus));
            }
            final long _tmpEditedAt;
            _tmpEditedAt = _cursor.getLong(_cursorIndexOfEditedAt);
            final long _tmpTokens;
            _tmpTokens = _cursor.getLong(_cursorIndexOfTokens);
            _result = new Message(_tmpId,_tmpSessionId,_tmpRole,_tmpContent,_tmpTimestamp,_tmpIsStreaming,_tmpAttachmentUrl,_tmpAttachmentType,_tmpAttachmentName,_tmpReplyToText,_tmpReaction,_tmpStatus,_tmpEditedAt,_tmpTokens);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }

  private String __MessageRole_enumToString(@NonNull final MessageRole _value) {
    switch (_value) {
      case USER: return "USER";
      case ASSISTANT: return "ASSISTANT";
      default: throw new IllegalArgumentException("Can't convert enum to string, unknown enum value: " + _value);
    }
  }

  private String __MessageStatus_enumToString(@NonNull final MessageStatus _value) {
    switch (_value) {
      case SENDING: return "SENDING";
      case SENT: return "SENT";
      case READ: return "READ";
      case FAILED: return "FAILED";
      default: throw new IllegalArgumentException("Can't convert enum to string, unknown enum value: " + _value);
    }
  }

  private MessageRole __MessageRole_stringToEnum(@NonNull final String _value) {
    switch (_value) {
      case "USER": return MessageRole.USER;
      case "ASSISTANT": return MessageRole.ASSISTANT;
      default: throw new IllegalArgumentException("Can't convert value to enum, unknown value: " + _value);
    }
  }

  private MessageStatus __MessageStatus_stringToEnum(@NonNull final String _value) {
    switch (_value) {
      case "SENDING": return MessageStatus.SENDING;
      case "SENT": return MessageStatus.SENT;
      case "READ": return MessageStatus.READ;
      case "FAILED": return MessageStatus.FAILED;
      default: throw new IllegalArgumentException("Can't convert value to enum, unknown value: " + _value);
    }
  }
}
