package com.quran.labs.androidquran.ui.bookmark;

import com.quran.labs.androidquran.dao.Bookmark;
import com.quran.labs.androidquran.dao.BookmarkData;
import com.quran.labs.androidquran.dao.Tag;
import com.quran.labs.androidquran.data.Constants;
import com.quran.labs.androidquran.database.BookmarksDBAdapter;
import com.quran.labs.androidquran.ui.helpers.QuranRow;
import com.quran.labs.androidquran.ui.helpers.QuranRowFactory;
import com.quran.labs.androidquran.util.QuranSettings;

import android.content.Context;
import android.support.annotation.VisibleForTesting;
import android.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import rx.Observable;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class BookmarkModel {
  private static final long BOOKMARKS_WITHOUT_TAGS_ID = -1;

  private static BookmarkModel sInstance;

  private final Context mAppContext;
  private final QuranSettings mQuranSettings;
  private final BookmarksDBAdapter mBookmarksDBAdapter;

  public static synchronized BookmarkModel getInstance(Context context) {
    if (sInstance == null) {
      sInstance = new BookmarkModel(context);
    }
    return sInstance;
  }

  private BookmarkModel(Context context) {
    mAppContext = context.getApplicationContext();
    mQuranSettings = QuranSettings.getInstance(context);
    mBookmarksDBAdapter = new BookmarksDBAdapter(context);
  }

  @VisibleForTesting
  BookmarkModel(Context appContext, QuranSettings settings, BookmarksDBAdapter adapter) {
    mAppContext = appContext;
    mQuranSettings = settings;
    mBookmarksDBAdapter = adapter;
  }

  public Observable<BookmarkResult> getBookmarkObservable(
      final int sortOrder, final boolean groupByTags) {
    return Observable
        .fromCallable(new Callable<BookmarkData>() {
          @Override
          public BookmarkData call() throws Exception {
            return fetchBookmarkData(sortOrder);
          }
        })
        .map(new Func1<BookmarkData, BookmarkResult>() {
          @Override
          public BookmarkResult call(BookmarkData bookmarkData) {
            List<QuranRow> rows = getBookmarkRows(bookmarkData, groupByTags);
            Map<Long, Tag> tagMap = generateTagMap(bookmarkData.getTags());
            return new BookmarkResult(rows, tagMap);
          }
        })
        .subscribeOn(Schedulers.io());
  }

  public Observable<Void> removeItemsObservable(
      final List<QuranRow> itemsToRemoveRef) {
    return Observable.fromCallable(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        List<Long> tagsToDelete = new ArrayList<>();
        List<Long> bookmarksToDelete = new ArrayList<>();
        List<Pair<Long, Long>> untag = new ArrayList<>();
        for (int i = 0, size = itemsToRemoveRef.size(); i < size; i++) {
          QuranRow row = itemsToRemoveRef.get(i);
          if (row.isBookmarkHeader() && row.tagId > 0) {
            tagsToDelete.add(row.tagId);
          } else if (row.isBookmark() && row.bookmarkId > 0) {
            if (row.tagId > 0) {
              untag.add(new Pair<>(row.bookmarkId, row.tagId));
            } else {
              bookmarksToDelete.add(row.bookmarkId);
            }
          }
        }
        mBookmarksDBAdapter.bulkDelete(tagsToDelete, bookmarksToDelete, untag);
        return null;
      }
    });
  }

  private List<QuranRow> getRowsSortedByTags(List<Tag> tags, List<Bookmark> bookmarks) {
    List<QuranRow> rows = new ArrayList<>();
    // sort by tags, alphabetical
    Map<Long, List<Bookmark>> tagsMapping = generateTagsMapping(tags, bookmarks);
    for (int i = 0, tagsSize = tags.size(); i < tagsSize; i++) {
      Tag tag = tags.get(i);
      rows.add(QuranRowFactory.fromTag(tag));
      List<Bookmark> tagBookmarks = tagsMapping.get(tag.id);
      for (int j = 0, tagBookmarksSize = tagBookmarks.size(); j < tagBookmarksSize; j++) {
        rows.add(QuranRowFactory.fromBookmark(mAppContext, tagBookmarks.get(j), tag.id));
      }
    }

    // add untagged bookmarks
    List<Bookmark> untagged = tagsMapping.get(BOOKMARKS_WITHOUT_TAGS_ID);
    if (untagged.size() > 0) {
      rows.add(QuranRowFactory.fromNotTaggedHeader(mAppContext));
      for (int i = 0, untaggedSize = untagged.size(); i < untaggedSize; i++) {
        rows.add(QuranRowFactory.fromBookmark(mAppContext, untagged.get(i)));
      }
    }
    return rows;
  }

  private List<QuranRow> getSortedRows(List<Bookmark> bookmarks) {
    List<QuranRow> rows = new ArrayList<>(bookmarks.size());
    List<Bookmark> ayahBookmarks = new ArrayList<>();

    // add the page bookmarks directly, save ayah bookmarks for later
    for (int i = 0, bookmarksSize = bookmarks.size(); i < bookmarksSize; i++) {
      Bookmark bookmark = bookmarks.get(i);
      if (bookmark.isPageBookmark()) {
        rows.add(QuranRowFactory.fromBookmark(mAppContext, bookmark));
      } else {
        ayahBookmarks.add(bookmark);
      }
    }

    // add page bookmarks header if needed
    if (rows.size() > 0) {
      rows.add(0, QuranRowFactory.fromPageBookmarksHeader(mAppContext));
    }

    // add ayah bookmarks if any
    if (ayahBookmarks.size() > 0) {
      rows.add(QuranRowFactory.fromAyahBookmarksHeader(mAppContext));
      for (int i = 0, ayahBookmarksSize = ayahBookmarks.size(); i < ayahBookmarksSize; i++) {
        rows.add(QuranRowFactory.fromBookmark(mAppContext, ayahBookmarks.get(i)));
      }
    }

    return rows;
  }

  private List<QuranRow> getBookmarkRows(BookmarkData data, boolean groupByTags) {
    List<QuranRow> rows;

    List<Tag> tags = data.getTags();
    List<Bookmark> bookmarks = data.getBookmarks();

    if (groupByTags) {
      rows = getRowsSortedByTags(tags, bookmarks);
    } else {
      rows = getSortedRows(bookmarks);
    }

    int lastPage = mQuranSettings.getLastPage();
    boolean showLastPage = lastPage != Constants.NO_PAGE_SAVED;
    if (showLastPage && (lastPage > Constants.PAGES_LAST || lastPage < Constants.PAGES_FIRST)) {
      showLastPage = false;
      Timber.w("Got invalid last saved page as %d", lastPage);
    }

    if (showLastPage) {
      rows.add(0, QuranRowFactory.fromCurrentPageHeader(mAppContext));
      rows.add(1, QuranRowFactory.fromCurrentPage(mAppContext, lastPage));
    }

    return rows;
  }

  private BookmarkData fetchBookmarkData(int sortOrder) {
    List<Bookmark> bookmarks = mBookmarksDBAdapter.getBookmarks(sortOrder);
    List<Tag> tags = mBookmarksDBAdapter.getTags();
    return new BookmarkData(tags, bookmarks);
  }

  private Map<Long, List<Bookmark>> generateTagsMapping(
      List<Tag> tags, List<Bookmark> bookmarks) {
    Set<Long> seenBookmarks = new HashSet<>();
    Map<Long, List<Bookmark>> tagMappings = new HashMap<>();
    for (int i = 0, tagSize = tags.size(); i < tagSize; i++) {
      long id = tags.get(i).id;
      List<Bookmark> matchingBookmarks = new ArrayList<>();
      for (int j = 0, bookmarkSize = bookmarks.size(); j < bookmarkSize; j++) {
        Bookmark bookmark = bookmarks.get(j);
        if (bookmark.tags.contains(id)) {
          matchingBookmarks.add(bookmark);
          seenBookmarks.add(bookmark.id);
        }
      }
      tagMappings.put(id, matchingBookmarks);
    }

    List<Bookmark> untaggedBookmarks = new ArrayList<>();
    for (int i = 0, bookmarksSize = bookmarks.size(); i < bookmarksSize; i++) {
      Bookmark bookmark = bookmarks.get(i);
      if (!seenBookmarks.contains(bookmark.id)) {
        untaggedBookmarks.add(bookmark);
      }
    }
    tagMappings.put(BOOKMARKS_WITHOUT_TAGS_ID, untaggedBookmarks);

    return tagMappings;
  }

  private Map<Long, Tag> generateTagMap(List<Tag> tags) {
    Map<Long, Tag> tagMap = new HashMap<>(tags.size());
    for (int i = 0, size = tags.size(); i < size; i++) {
      Tag tag = tags.get(i);
      tagMap.put(tag.id, tag);
    }
    return tagMap;
  }
}