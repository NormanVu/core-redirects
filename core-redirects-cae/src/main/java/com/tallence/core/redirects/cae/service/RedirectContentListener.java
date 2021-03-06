/*
 * Copyright 2019 Tallence AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tallence.core.redirects.cae.service;

import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.events.ContentDestroyedEvent;
import com.coremedia.cap.content.events.ContentEvent;
import com.coremedia.cap.content.events.ContentRepositoryEventConstants;
import com.coremedia.cap.content.events.ContentRepositoryListenerBase;
import com.coremedia.cap.content.publication.events.PublicationContentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener for updates to the redirects.
 */
public class RedirectContentListener extends ContentRepositoryListenerBase {

  private static final Logger LOG = LoggerFactory.getLogger(RedirectContentListener.class);

  private final RedirectUpdateTaskScheduler redirectUpdateTaskScheduler;

  public RedirectContentListener(RedirectUpdateTaskScheduler redirectUpdateTaskScheduler) {
    this.redirectUpdateTaskScheduler = redirectUpdateTaskScheduler;
  }

  @Override
  protected void handleContentEvent(ContentEvent event) {
    Content content = event.getContent();

    if (event instanceof ContentDestroyedEvent) {

      ContentDestroyedEvent destroyedEvent = (ContentDestroyedEvent) event;
      if (destroyedEvent.getContentType().isSubtypeOf("Redirect")) {
        redirectUpdateTaskScheduler.runDestroy(content.getId(), destroyedEvent.getFolder());
      }
      //content.getType will throw an exception for destroyed contents.
      return;
    }

    //Check isDestroyed again, the document might be destroyed in the meantime
    if (!content.isDestroyed() && content.getType().isSubtypeOf("Redirect")) {

      LOG.debug("Received new event of type [{}] for Redirect with id [{}]", event.getType(), content.getId());

      switch (event.getType()) {
        case ContentRepositoryEventConstants.CONTENT_CREATED:
        case ContentRepositoryEventConstants.CONTENT_CHECKED_IN:
        case ContentRepositoryEventConstants.CONTENT_UNDELETED:
        case ContentRepositoryEventConstants.CONTENT_REVERTED:
          redirectUpdateTaskScheduler.runUpdate(content);
          break;
        case ContentRepositoryEventConstants.CONTENT_DELETED:
        case ContentRepositoryEventConstants.CONTENT_DESTROYED:
          redirectUpdateTaskScheduler.runRemove(content);
          break;
        case ContentRepositoryEventConstants.CONTENT_MOVED:
          // FIXME What to do here??
          break;
      }
    }
  }

  @Override
  protected void handlePublicationContentEvent(PublicationContentEvent event) {
    super.handlePublicationContentEvent(event);
  }
}

