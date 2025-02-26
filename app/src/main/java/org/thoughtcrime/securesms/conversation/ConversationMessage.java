package org.thoughtcrime.securesms.conversation;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import org.signal.core.util.Conversions;
import org.thoughtcrime.securesms.components.mention.MentionAnnotation;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MentionUtil;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.MessageRecord;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

/**
 * A view level model used to pass arbitrary message related information needed
 * for various presentations.
 */
public class ConversationMessage {
  @NonNull  private final MessageRecord   messageRecord;
  @NonNull  private final List<Mention>   mentions;
  @Nullable private final SpannableString body;

  private ConversationMessage(@NonNull MessageRecord messageRecord) {
    this(messageRecord, null, null);
  }

  private ConversationMessage(@NonNull MessageRecord messageRecord,
                              @Nullable CharSequence body,
                              @Nullable List<Mention> mentions)
  {
    this.messageRecord = messageRecord;
    this.body          = body != null ? SpannableString.valueOf(body) : null;
    this.mentions      = mentions != null ? mentions : Collections.emptyList();

    if (!this.mentions.isEmpty() && this.body != null) {
      MentionAnnotation.setMentionAnnotations(this.body, this.mentions);
    }
  }

  public @NonNull MessageRecord getMessageRecord() {
    return messageRecord;
  }

  public @NonNull List<Mention> getMentions() {
    return mentions;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    final ConversationMessage that = (ConversationMessage) o;
    return messageRecord.equals(that.messageRecord);
  }

  @Override
  public int hashCode() {
    return messageRecord.hashCode();
  }

  public long getUniqueId(@NonNull MessageDigest digest) {
    String unique = (messageRecord.isMms() ? "MMS::" : "SMS::") + messageRecord.getId();
    byte[] bytes  = digest.digest(unique.getBytes());

    return Conversions.byteArrayToLong(bytes);
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private static SpannableString applyFormatting(SpannableString str, char formatter, Object style) {
    String resultString = str.toString();
    int index = resultString.indexOf(formatter);
    int next = -1;

    if (index == -1) {
      return str;
    }

    SpannableStringBuilder builder = new SpannableStringBuilder("");
    if (index != 0) {
      builder.append(str, 0, index);
    }

    while (index != -1) {
      if (next != -1) {
        builder.append(str, next + 1, index);
      }

      next = resultString.indexOf(formatter, index + 1);
      if (next == -1) {
        builder.append(str, index, resultString.length());
        break;
      }

      builder.append(resultString.subSequence(index + 1, next), style, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
      index = resultString.indexOf(formatter, next + 1);
    }

    if ((next != -1) && (next < resultString.length() - 1)) {
      builder.append(str, next + 1, resultString.length());
    }

    return SpannableString.valueOf(builder);
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public @NonNull SpannableString getDisplayBody(Context context) {
    SpannableString str;

    if (mentions.isEmpty() || body == null) {
      str = messageRecord.getDisplayBody(context);
    }
    else {
      str = body;
    }
    return  applyFormatting(str, '*', new StyleSpan(Typeface.BOLD));
  }

  /**
   * Factory providing multiple ways of creating {@link ConversationMessage}s.
   */
  public static class ConversationMessageFactory {

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord. No database or
     * heavy work performed as the message is assumed to not have any mentions.
     */
    @AnyThread
    public static @NonNull ConversationMessage createWithResolvedData(@NonNull MessageRecord messageRecord) {
      return new ConversationMessage(messageRecord);
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord, potentially annotated body, and
     * list of actual mentions. No database or heavy work performed as the body and mentions are assumed to be
     * fully updated with display names.
     *
     * @param body     Contains appropriate {@link MentionAnnotation}s and is updated with actual profile names.
     * @param mentions List of actual mentions (i.e., not placeholder) matching annotation ranges in body.
     */
    @AnyThread
    public static @NonNull ConversationMessage createWithResolvedData(@NonNull MessageRecord messageRecord, @Nullable CharSequence body, @Nullable List<Mention> mentions) {
      if (messageRecord.isMms() && mentions != null && !mentions.isEmpty()) {
        return new ConversationMessage(messageRecord, body, mentions);
      }
      return createWithResolvedData(messageRecord);
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord and will update and modify the provided
     * mentions from placeholder to actual. This method may perform database operations to resolve mentions to display names.
     *
     * @param mentions List of placeholder mentions to be used to update the body in the provided MessageRecord.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context, @NonNull MessageRecord messageRecord, @Nullable List<Mention> mentions) {
      if (messageRecord.isMms() && mentions != null && !mentions.isEmpty()) {
        MentionUtil.UpdatedBodyAndMentions updated = MentionUtil.updateBodyAndMentionsWithDisplayNames(context, messageRecord, mentions);
        return new ConversationMessage(messageRecord, updated.getBody(), updated.getMentions());
      }
      return createWithResolvedData(messageRecord);
    }

    /**
     * Creates a {@link ConversationMessage} wrapping the provided MessageRecord, and will query for potential mentions. If mentions
     * are found, the body of the provided message will be updated and modified to match actual mentions. This will perform
     * database operations to query for mentions and then to resolve mentions to display names.
     */
    @WorkerThread
    public static @NonNull ConversationMessage createWithUnresolvedData(@NonNull Context context, @NonNull MessageRecord messageRecord) {
      if (messageRecord.isMms()) {
        List<Mention> mentions = DatabaseFactory.getMentionDatabase(context).getMentionsForMessage(messageRecord.getId());
        if (!mentions.isEmpty()) {
          MentionUtil.UpdatedBodyAndMentions updated = MentionUtil.updateBodyAndMentionsWithDisplayNames(context, messageRecord, mentions);
          return new ConversationMessage(messageRecord, updated.getBody(), updated.getMentions());
        }
      }
      return createWithResolvedData(messageRecord);
    }
  }
}
