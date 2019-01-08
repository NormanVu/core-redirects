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

package com.tallence.core.redirects.studio.repository;

import com.coremedia.cap.common.IdHelper;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import com.coremedia.cap.content.publication.PublicationHelper;
import com.coremedia.cap.content.publication.PublicationService;
import com.coremedia.cap.multisite.Site;
import com.coremedia.cap.multisite.SitesService;
import com.coremedia.rest.cap.content.search.SearchServiceResult;
import com.coremedia.rest.cap.content.search.solr.SolrSearchService;
import com.tallence.core.redirects.model.RedirectType;
import com.tallence.core.redirects.model.SourceUrlType;
import com.tallence.core.redirects.studio.model.Pageable;
import com.tallence.core.redirects.studio.model.Redirect;
import com.tallence.core.redirects.studio.model.RedirectImpl;
import com.tallence.core.redirects.studio.model.RedirectUpdateProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.function.Supplier;

/**
 * Default implementation of a {@link RedirectRepository}.
 * This implementation stores the redirects as content objects in a settings folder.
 */
public class RedirectRepositoryImpl implements RedirectRepository {

  private static final Logger LOG = LoggerFactory.getLogger(RedirectRepositoryImpl.class);

  private static final String REDIRECT_PATH = "Options/Settings/Redirects";

  private static final String DESCRIPTION = "description";
  private static final String IMPORTED = "imported";
  private static final String SOURCE = "source";
  private static final String SOURCE_URL_TYPE = "sourceUrlType";
  private static final String REDIRECT_TYPE = "redirectType";
  private static final String TARGET_LINK = "targetLink";

  private final SolrSearchService solrSearchService;
  private final SitesService sitesService;
  private final ContentRepository contentRepository;
  private final PublicationHelper publicationHelper;
  private final PublicationService publicationService;

  private ContentType redirectContentType;

  @Autowired
  public RedirectRepositoryImpl(SolrSearchService solrSearchService, ContentRepository contentRepository, SitesService sitesService) {
    this.solrSearchService = solrSearchService;
    this.contentRepository = contentRepository;
    this.sitesService = sitesService;
    redirectContentType = contentRepository.getContentType("Redirect");
    this.publicationService = contentRepository.getPublicationService();
    this.publicationHelper = new PublicationHelper(contentRepository);
  }

  @Override
  public Redirect createRedirect(String siteId, RedirectUpdateProperties updateProperties) {
    Optional<String> source = updateProperties.getSource();
    if (source.isPresent() && redirectAlreadyExists(siteId, source.get())) {
      throw new IllegalArgumentException("duplicated source url");
    }
    String uuid = UUID.randomUUID().toString();
    Content redirect = contentRepository.createChild(getFolderForRedirect(siteId, uuid), uuid, redirectContentType, new HashMap<>());
    updateRedirect(redirect, false, updateProperties);
    return convertToRedirect(redirect);
  }

  @Override
  public boolean sourceAlreadyExists(String siteId, String redirectId, String source) {
    return redirectAlreadyExists(siteId, redirectId, source);
  }

  @Override
  public boolean sourceAlreadyExists(String siteId, String source) {
    return redirectAlreadyExists(siteId, source);
  }

  @Override
  public boolean sourceIsValid(String source) {
    return StringUtils.isNotEmpty(source) && source.startsWith("/");
  }

  @Override
  public Redirect getRedirect(String id) {
    return convertToRedirect(contentRepository.getContent(id));
  }

  @Override
  public void updateRedirect(String id, RedirectUpdateProperties updateProperties) {
    Content redirect = contentRepository.getContent(id);
    boolean wasPublished = publicationService.isPublished(redirect);
    if (redirect.isCheckedOut() && !redirect.isCheckedOutByCurrentSession()) {
      LOG.error("Content {} is checked out by other user, could not update redirect", redirect.getId());
      throw new IllegalArgumentException("Redirect is checked out by other user.");
    }
    if (redirect.isCheckedIn()) {
      redirect.checkOut();
    }
    updateRedirect(redirect, wasPublished, updateProperties);
  }

  @Override
  public void deleteRedirect(String id) {
    Content redirect = contentRepository.getContent(id);
    if (redirect.isCheckedOut() && !redirect.isCheckedOutByCurrentSession()) {
      LOG.error("Content {} is checked out by other user, could not delete redirect", redirect.getId());
      throw new IllegalArgumentException("Redirect is checked out by other user.");
    }
    if (redirect.isCheckedOut()) {
      redirect.checkIn();
    }
    redirect.delete();
  }

  @Override
  public Pageable getRedirects(String siteId, String search, String sorter, String sortDirection, int pageSize, int page) {
    String query = StringUtils.isEmpty(StringUtils.trim(search)) ? "source:*" : "source:*" + ClientUtils.escapeQueryChars(StringUtils.trim(search)) + "*";

    List<String> sortCriteria;
    if (StringUtils.isNotEmpty(sorter) && StringUtils.isNotEmpty(sortDirection)) {
      sortCriteria = Collections.singletonList(sorter.toLowerCase() + " " + sortDirection.toLowerCase());
    } else {
      sortCriteria = Collections.emptyList();
    }

    SearchServiceResult result = search(siteId, Arrays.asList("isdeleted:false", query), sortCriteria);
    List<Content> hits = result.getHits();
    List<Redirect> redirects = new ArrayList<>();
    for (int i = (page - 1) * pageSize; i < page * pageSize; i++) {
      if (i < hits.size()) {
        redirects.add(convertToRedirect(hits.get(i)));
      }
    }

    return new Pageable(redirects, Math.toIntExact(result.getTotal()));
  }

  private boolean redirectAlreadyExists(String siteId, String redirectId, String source) {
    if (StringUtils.isEmpty(source)) {
      return false;
    }
    List<String> filterQueries = Arrays.asList("isdeleted:false", "source:" + ClientUtils.escapeQueryChars(source), "-numericid:" + redirectId);
    return !search(siteId, filterQueries, Collections.emptyList()).getHits().isEmpty();
  }

  private boolean redirectAlreadyExists(String siteId, String source) {
    if (StringUtils.isEmpty(source)) {
      return false;
    }
    List<String> filterQueries = Arrays.asList("isdeleted:false", "source:" + ClientUtils.escapeQueryChars(source));
    return !search(siteId, filterQueries, Collections.emptyList()).getHits().isEmpty();
  }

  /**
   * Uses the {@link SolrSearchService} to search for redirects.
   *
   * @param siteId        The site id.
   * @param filterQueries The filter queries.
   * @return the search result
   */
  private SearchServiceResult search(String siteId, List<String> filterQueries, List<String> sortCriteria) {
    return solrSearchService.search(
        "*",
        1000,
        sortCriteria,
        getRedirectsFolder(siteId),
        true,
        Collections.singletonList(redirectContentType),
        false,
        filterQueries,
        new ArrayList<>(),
        new ArrayList<>());
  }

  /**
   * Returns redirects folder for the given site. If no site is found, the root folder is used as fallback folder.
   *
   * @param siteId the site id
   * @return the folder
   */
  private Content getRedirectsFolder(String siteId) {
    Site site = sitesService.getSite(siteId);
    if (site != null) {
      if (site.getSiteRootFolder().hasChild(REDIRECT_PATH)) {
        return site.getSiteRootFolder().getChild(REDIRECT_PATH);
      }
      return site.getSiteRootFolder();
    }
    return contentRepository.getRoot().getChild(REDIRECT_PATH);
  }

  /**
   * Returns the folder for the new redirect. Redirects a stored in sub folders under the redirects root folder.
   *
   * @param siteId the site id
   * @param uuid   the uuid for the new redirect.
   * @return the folder
   */
  private Content getFolderForRedirect(String siteId, String uuid) {
    Site site = sitesService.getSite(siteId);
    String pathToRedirectFolder = REDIRECT_PATH + "/" + uuid.substring(0, 2);
    Content rootFolder = site != null ? site.getSiteRootFolder() : contentRepository.getRoot();

    if (!rootFolder.hasChild(pathToRedirectFolder)) {
      contentRepository.createSubfolders(rootFolder, pathToRedirectFolder);
    }
    return rootFolder.getChild(pathToRedirectFolder);
  }

  /**
   * Converts a {@link Content} object to a {@link Redirect}
   *
   * @param redirectEntry the redirect content object.
   * @return the redirect.
   */
  private Redirect convertToRedirect(Content redirectEntry) {
    Site site = sitesService.getContentSiteAspect(redirectEntry).getSite();
    return new RedirectImpl(
        String.valueOf(IdHelper.parseContentId(redirectEntry.getId())),
        site != null ? site.getId() : null,
        publicationService.isPublished(redirectEntry),
        SourceUrlType.asSourceUrlType(redirectEntry.getString(SOURCE_URL_TYPE)),
        redirectEntry.getString(SOURCE),
        redirectEntry.getCreationDate().getTime(),
        redirectEntry.getLink(TARGET_LINK),
        RedirectType.asRedirectType(redirectEntry.getString(REDIRECT_TYPE)),
        redirectEntry.getString(DESCRIPTION),
        redirectEntry.getBoolean(IMPORTED)
    );
  }

  /**
   * Updates a redirect content object.
   *
   * @param redirect         the redirect content object.
   * @param wasPublished     true, if the content was published.
   * @param updateProperties the redirect update properties.
   */
  private void updateRedirect(Content redirect, boolean wasPublished, RedirectUpdateProperties updateProperties) {
    updateProperty(updateProperties::getDescription, DESCRIPTION, redirect);
    updateBooleanProperty(updateProperties::getImported, IMPORTED, redirect);
    updateProperty(updateProperties::getSource, SOURCE, redirect);
    updateLinkProperty(updateProperties::getTargetLink, TARGET_LINK, redirect);
    updateEnumProperty(updateProperties::getRedirectType, REDIRECT_TYPE, redirect);
    updateEnumProperty(updateProperties::getSourceUrlType, SOURCE_URL_TYPE, redirect);

    Optional<Boolean> active = updateProperties.getActive();
    if (active.isPresent()) {
      // if active is set to false, the redirect must be withdrawn
      publicationHelper.publish(redirect, active.get());
    } else if (wasPublished) {
      publicationHelper.publish(redirect);
    }
  }

  private void updateProperty(Supplier<Optional> supplier, String property, Content redirect) {
    Optional value = supplier.get();
    if (value.isPresent()) {
      redirect.set(property, value.get());
    }
  }

  private void updateEnumProperty(Supplier<Optional> supplier, String propertyName, Content redirect) {
    Optional value = supplier.get();
    if (value.isPresent()) {
      redirect.set(propertyName, value.get().toString());
    }
  }

  private void updateLinkProperty(Supplier<Optional> supplier, String propertyName, Content redirect) {
    Optional value = supplier.get();
    if (value.isPresent()) {
      redirect.set(propertyName, Collections.singletonList(value.get()));
    }
  }

  private void updateBooleanProperty(Supplier<Optional> supplier, String propertyName, Content redirect) {
    Optional value = supplier.get();
    if (value.isPresent()) {
      redirect.set(propertyName, value.get().equals(true) ? 1 : 0);
    }
  }

}