/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.smssecure.smssecure.mms;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.util.MediaUtil;
import org.whispersystems.libaxolotl.util.guava.Optional;

import java.util.LinkedList;
import java.util.List;

public class SlideDeck {

  private final List<Slide> slides = new LinkedList<>();

  public SlideDeck(Context context, List<Attachment> attachments) {
    for (Attachment attachment : attachments) {
      Slide slide = MediaUtil.getSlideForAttachment(context, attachment);
      if (slide != null) slides.add(slide);
    }
  }

  public SlideDeck(Context context, Attachment attachment) {
    Slide slide = MediaUtil.getSlideForAttachment(context, attachment);
    if (slide != null) slides.add(slide);
  }

  public SlideDeck() {
  }

  public void clear() {
    slides.clear();
  }

  @NonNull
  public String getBody() {
    String body = "";

    for (Slide slide : slides) {
      Optional<String> slideBody = slide.getBody();

      if (slideBody.isPresent()) {
        body = slideBody.get();
      }
    }

    return body;
  }

  @NonNull
  public List<Attachment> asAttachments() {
    List<Attachment> attachments = new LinkedList<>();

    for (Slide slide : slides) {
      attachments.add(slide.asAttachment());
    }

    return attachments;
  }

  public void addSlide(Slide slide) {
    slides.add(slide);
  }

  public List<Slide> getSlides() {
    return slides;
  }

  public boolean containsMediaSlide() {
    for (Slide slide : slides) {
      if (slide.hasImage() || slide.hasVideo() || slide.hasAudio() || slide.hasFile()) {
        return true;
      }
    }
    return false;
  }

  public boolean removeFileSlides() {
    boolean returnObject = false;
    if(slides.isEmpty())
      return returnObject;
    for (Slide slide : slides) {
      if (slide.hasFile()) {
        returnObject = true;
        slides.remove(slide);
      }
    }
    return returnObject;
  }

  public boolean hasFileSlide() {
    for (Slide slide : slides) {
      if (slide.hasFile()) {
        return true;
      }
    }
    return false;
  }

  public @Nullable Slide getThumbnailSlide() {
    for (Slide slide : slides) {
      if (slide.hasImage()) {
        return slide;
      }
    }

    return null;
  }

  public @Nullable AudioSlide getAudioSlide() {
    for (Slide slide : slides) {
      if (slide.hasAudio()) {
        return (AudioSlide)slide;
      }
    }

    return null;
  }
}
