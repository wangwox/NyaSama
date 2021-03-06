package com.nyasama.activity;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.android.volley.Response;
import com.android.volley.toolbox.NetworkImageView;
import com.negusoft.holoaccent.dialog.AccentAlertDialog;
import com.negusoft.holoaccent.dialog.DividerPainter;
import com.nyasama.R;
import com.nyasama.ThisApp;
import com.nyasama.fragment.CommonListFragment;
import com.nyasama.util.BitmapLruCache;
import com.nyasama.util.CallbackMatcher;
import com.nyasama.util.CommonListAdapter;
import com.nyasama.util.Discuz;
import com.nyasama.util.Discuz.Attachment;
import com.nyasama.util.Discuz.Comment;
import com.nyasama.util.Discuz.PollOption;
import com.nyasama.util.Discuz.Post;
import com.nyasama.util.Helper;
import com.nyasama.util.HtmlImageGetter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PostListActivity extends BaseThemedActivity
    implements CommonListFragment.OnListFragmentInteraction<Post> {

    public static final int REQUEST_CODE_EDIT_POST = 1;
    public static final int REQUEST_CODE_REPLY_THREAD = 2;

    private static final int PAGE_SIZE_COUNT = 10;
    private static final int COMMENT_PAGE_SIZE = 10;
    private static final int MAX_TRIMSTR_LENGTH = 30;

    private boolean isPaused = false;

    private CommonListFragment<Post> mListFragment;
    private int mListPages;

    private Map<String, Attachment> mAttachmentMap = new HashMap<String, Attachment>();
    private AlertDialog mReplyDialog;

    private SparseArray<List<Comment>> mComments = new SparseArray<List<Comment>>();
    private SparseArray<Integer> mCommentCount = new SparseArray<Integer>();
    private AlertDialog mCommentDialog;

    private List<PollOption> mPollOptions = new ArrayList<PollOption>();
    private boolean mAllowVote;
    private int mMaxChoices;
    private AlertDialog mVoteDialog;

    private int mForumId;
    private int mAuthorId;
    private int mThreadPrice;

    private Point mPrefMaxImageSize = new Point(-1, -1);
    private int mPrefFontSize = 16;

    private int mSelectedPost;
    private AlertDialog mThreadModerateDialog;

    public void setupActionBarPages(int pages) {
        final ActionBar actionBar = getActionBar();
        if (actionBar == null) return;

        final Intent intent = getIntent();
        int authorId = intent.getIntExtra("authorid", 0);
        boolean reversed = intent.getBooleanExtra("reverse", false);
        final String title = actionBar.getTitle() != null ? actionBar.getTitle().toString() : "";
        final String sub =
                (authorId == mAuthorId ? getString(R.string.action_see_author) : "") + " " +
                (reversed ? getString(R.string.action_reverse_order) : "");

        if (pages <= 1 && authorId == 0 && !reversed) {
            actionBar.setDisplayShowTitleEnabled(true);
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            return;
        }

        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);

        final List<String> pageNames = new ArrayList<String>();
        for (int i = 0; i < (mListPages = pages); i ++)
            pageNames.add(String.format(getString(R.string.page_index), i + 1));
        ArrayAdapter adapter = new ArrayAdapter<String>(actionBar.getThemedContext(),
                R.layout.fragment_spinner_item_2, android.R.id.text1, pageNames) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ((TextView) view.findViewById(android.R.id.text1)).setText(
                        pageNames.get(position) + " " + sub);
                ((TextView) view.findViewById(android.R.id.text2)).setText(title);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        actionBar.setListNavigationCallbacks(adapter, new ActionBar.OnNavigationListener() {
            @Override
            public boolean onNavigationItemSelected(int i, long l) {
                if (intent.getBooleanExtra("update-nav-spinner", false)) {
                    intent.putExtra("update-nav-spinner", false);
                }
                else if (intent.getIntExtra("page", 0) != i) {
                    intent.putExtra("page", i);
                    mListFragment.reloadAll();
                }
                return false;
            }
        });
        actionBar.setSelectedNavigationItem(intent.getIntExtra("page", 0));
    }

    public void loadDisplayPreference() {
        String displayImageSetting =
                ThisApp.preferences.getString(getString(R.string.pref_key_show_image), "");

        boolean shallDisplayImage = !"false".equals(displayImageSetting);
        if ("auto".equals(displayImageSetting)) {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo =
                    connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            shallDisplayImage = networkInfo.isConnected();
        }

        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        mPrefMaxImageSize.x = shallDisplayImage ? Helper.toSafeInteger(
                ThisApp.preferences.getString(getString(R.string.pref_key_thumb_size), ""), -1) : -1;
        if (mPrefMaxImageSize.x == 0) mPrefMaxImageSize.x = size.x * 4 / 5;
        mPrefMaxImageSize.y = mPrefMaxImageSize.x * size.y / size.x;

        mPrefFontSize = Helper.toSafeInteger(
                ThisApp.preferences.getString(getString(R.string.pref_key_text_size), ""), 16);
    }

    public void doReply(final String text, final String trimstr) {
        Helper.enableDialog(mReplyDialog, false);
        Discuz.execute("sendreply", new HashMap<String, Object>() {{
            put("tid", getIntent().getIntExtra("tid", 0));
            put("replysubmit", "yes");
        }}, new HashMap<String, Object>() {{
            put("message", text);
            if (trimstr != null)
                put("noticetrimstr", trimstr);
        }}, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                if (mReplyDialog != null)
                    mReplyDialog.dismiss();
                if (data.has(Discuz.VOLLEY_ERROR)) {
                    Helper.toast(R.string.network_error_toast);
                }
                else if (data.opt("Message") instanceof JSONObject) {
                    JSONObject message = data.optJSONObject("Message");
                    String messageval = message.optString("messageval");
                    if ("post_reply_succeed".equals(messageval)) {
                        mListFragment.reloadLast();
                    }
                    else if ("replyperm_login_nopermission//1".equals(messageval)) {
                        startActivity(new Intent(PostListActivity.this, LoginActivity.class));
                    }
                    else
                        Helper.toast(message.optString("messagestr"));
                }
            }
        });
    }

    public void doComment(final int pid, final String comment) {
        Helper.enableDialog(mCommentDialog, false);
        Discuz.execute("addcomment", new HashMap<String, Object>() {{
            put("tid", getIntent().getIntExtra("tid", 0));
            put("pid", pid);
        }}, new HashMap<String, Object>() {{
            put("message", comment);
        }}, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                if (data.has(Discuz.VOLLEY_ERROR)) {
                    Helper.toast(R.string.network_error_toast);
                    Helper.enableDialog(mCommentDialog, true);
                }
                else if (data.opt("Message") instanceof JSONObject) {
                    JSONObject message = data.optJSONObject("Message");
                    String messageval = message.optString("messageval");
                    if ("comment_add_succeed".equals(messageval)) {
                        List<Comment> comments = mComments.get(pid);
                        if (comments == null)
                            mComments.put(pid, comments = new ArrayList<Comment>());
                        comments.add(0, new Comment(Discuz.sUid, Discuz.sUsername, comment));
                        mCommentCount.put(pid, comments.size());
                        mListFragment.getListAdapter().notifyDataSetChanged();
                        mCommentDialog.dismiss();
                    }
                    else {
                        Helper.toast(message.optString("messagestr"));
                        Helper.enableDialog(mCommentDialog, true);
                    }
                }
            }
        });
    }

    public void doPollVote(final List<Integer> selected) {
        Helper.enableDialog(mVoteDialog, false);
        Discuz.execute("pollvote", new HashMap<String, Object>() {{
            put("fid", getIntent().getIntExtra("fid", 0));
            put("tid", getIntent().getIntExtra("tid", 0));
        }}, new HashMap<String, Object>() {{
            put("pollanswers[]", selected);
        }}, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                if (data.has(Discuz.VOLLEY_ERROR))
                    Helper.toast(R.string.there_is_something_wrong);
                else if (data.opt("Message") instanceof JSONObject) {
                    JSONObject message = data.optJSONObject("Message");
                    String messageval = message.optString("messageval");
                    if ("thread_poll_succeed".equals(messageval))
                        mListFragment.reloadAll();
                    Helper.toast(message.optString("messagestr"));
                }
                mVoteDialog.dismiss();
            }
        });
    }

    public void doLoadComment(final int pid, final int page) {
        Discuz.execute("morecomment", new HashMap<String, Object>() {{
            put("tid", getIntent().getIntExtra("tid", 0));
            put("pid", pid);
            put("page", page + 1);
        }}, null, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                mCommentCount.put(pid, Integer.MAX_VALUE);
                if (data.has(Discuz.VOLLEY_ERROR)) {
                    Helper.toast(R.string.network_error_toast);
                }
                else if (data.opt("Variables") instanceof JSONObject) {
                    JSONObject var = data.optJSONObject("Variables");
                    if (var.opt("comments") instanceof JSONArray) {
                        JSONArray commentList = var.optJSONArray("comments");
                        List<Comment> comments = mComments.get(pid);
                        Helper.setListLength(comments, page * COMMENT_PAGE_SIZE);
                        for (int i = 0; i < commentList.length(); i ++) {
                            comments.add(new Comment(commentList.optJSONObject(i)));
                        }
                        mCommentCount.put(pid, Helper.toSafeInteger(var.optString("count"), 0));
                        mListFragment.getListAdapter().notifyDataSetChanged();
                    }
                }
            }
        });
    }

    public void doModerateThread(final String operation, final String reason, final Object... args) {
        Helper.enableDialog(mThreadModerateDialog, false);
        Discuz.execute("topicadmin", new HashMap<String, Object>(){{
            if ("delpost".equals(operation) || "warn".equals(operation) || "banpost".equals(operation))
                put("action", operation);

            else
                put("action", "moderate");

            if ("bump".equals(operation) || "down".equals(operation) || "delete".equals(operation))
                put("optgroup", 3);

            else if ("open".equals(operation) || "close".equals(operation))
                put("optgroup", 4);

            else if ("stick".equals(operation))
                put("optgroup", 1);

            else if ("move".equals(operation))
                put("optgroup", 2);

        }}, new HashMap<String, Object>(){{
            put("fid", mForumId);
            put("reason", reason);

            if ("delpost".equals(operation) || "warn".equals(operation) || "banpost".equals(operation)) {
                put("topiclist[]", args[0]);
                put("tid", getIntent().getIntExtra("tid", 0));

                if ("warn".equals(operation))
                    put("warned", args[1]);
                else if ("banpost".equals(operation))
                    put("banned", args[1]);
            }
            else {
                put("moderate[]", getIntent().getIntExtra("tid", 0));
                put("operations[]", operation);
            }

            if ("stick".equals(operation)) {
                put("sticklevel", args[0]);
                put("expirationstick", args[1]);
            }
            else if ("highlight".equals(operation)) {
                put("highlight_color", args[0]);
                put("expirationhighlight", args[1]);
                put("highlight_style[1]", args[2]);
                put("highlight_style[2]", args[3]);
                put("highlight_style[3]", args[4]);
            }
            else if ("digest".equals(operation)) {
                put("digestlevel", args[0]);
                put("expirationdigest", args[1]);
            }
            else if ("move".equals(operation)) {
                put("moveto", args[0]);
                put("threadtypeid", args[1]);
                put("type", args[2]);
            }

        }}, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                JSONObject message = Helper.optJSONObject(jsonObject, "Message");
                if (message != null)
                    Helper.toast(message.optString("messagestr", getString(R.string.there_is_something_wrong)));
                mThreadModerateDialog.dismiss();

                // reload
                if ("delpost".equals(operation))
                    mListFragment.reloadAll();
                // close this thread if deleted
                else if ("delete".equals(operation))
                    PostListActivity.this.finish();

            }
        });
    }

    public void doMarkFavourite() {
        Discuz.execute("favthread", new HashMap<String, Object>() {{
            put("id", getIntent().getIntExtra("tid", 0));
        }}, new HashMap<String, Object>() {{
        }}, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                if (data.has(Discuz.VOLLEY_ERROR)) {
                    Helper.toast(R.string.network_error_toast);
                } else if (data.opt("Message") instanceof JSONObject) {
                    JSONObject message = data.optJSONObject("Message");
                    Helper.toast(message.optString("messagestr"));
                }
            }
        });
    }

    public void doBuyThread(final AlertDialog dialog) {
        Helper.enableDialog(dialog, false);
        Discuz.execute("buythread", new HashMap<String, Object>() {{
            put("tid", getIntent().getIntExtra("tid", 0));
        }}, new HashMap<String, Object>() {{
        }}, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                if (data.has(Discuz.VOLLEY_ERROR)) {
                    Helper.toast(R.string.network_error_toast);
                } else if (data.opt("Message") instanceof JSONObject) {
                    JSONObject message = data.optJSONObject("Message");
                    Helper.toast(message.optString("messagestr"));
                    if ("thread_pay_succeed".equals(message.optString("messageval")))
                        mListFragment.reloadAll();
                }
                dialog.dismiss();
            }
        });
    }

    public void editPost(Post item) {
        Intent intent = new Intent(this, NewPostActivity.class);
        intent.putExtra("tid", getIntent().getIntExtra("tid", 0));
        intent.putExtra("pid", item.id);
        startActivityForResult(intent, REQUEST_CODE_EDIT_POST);
    }

    public void quickReply(final Post item) {
        final EditText input = new EditText(this);
        mReplyDialog = new AccentAlertDialog.Builder(PostListActivity.this)
                .setTitle(R.string.diag_quick_reply_title)
                .setMessage(R.string.diag_hint_type_something)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mReplyDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                new DividerPainter(PostListActivity.this).paint(mReplyDialog.getWindow());
                mReplyDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            String text = input.getText().toString();
                            if (!text.isEmpty())
                                doReply(text, item == null ? null : getTrimstr(item));
                        }
                    });
            }
        });
        mReplyDialog.show();
    }

    public void addComment(Post item) {
        final int pid = item.id;
        final EditText input = new EditText(this);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i2, int i3) {
            }
            @Override
            public void afterTextChanged(Editable editable) {
                mCommentDialog.setMessage(getString(R.string.diag_hint_type_something) + " " +
                    "(" + editable.toString().length() + "/" + Discuz.MAX_COMMENT_LENGTH + ")");
            }
        });
        mCommentDialog = new AccentAlertDialog.Builder(PostListActivity.this)
                .setTitle(R.string.action_comment)
                .setMessage(R.string.diag_hint_type_something)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mCommentDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                new DividerPainter(PostListActivity.this).paint(mCommentDialog.getWindow());
                mCommentDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                String text = input.getText().toString();
                                if (!text.isEmpty())
                                    doComment(pid, text);
                            }
                        });
            }
        });
        mCommentDialog.show();
    }

    public void loadComment(Post item) {
        int count = mCommentCount.get(item.id);
        // give up if it's loading now
        if (count < 0)
            return;

        final int pid = item.id;
        List<Comment> comments = mComments.get(pid);
        if (comments.size() >= count)
            return;

        // set count to negative
        mCommentCount.put(pid, -1);
        doLoadComment(pid, (int) Math.floor(comments.size() / 10));
    }

    public void gotoReply(final Post item) {
        startActivityForResult(new Intent(PostListActivity.this, NewPostActivity.class) {{
            putExtra("tid", PostListActivity.this.getIntent().getIntExtra("tid", 0));
            if (item != null) {
                putExtra(NewPostActivity.ARG_POST_TITLE, "Re: " + item.author + " #" + mListFragment.getIndex(item));
                putExtra(NewPostActivity.ARG_POST_TRIMSTR, getTrimstr(item));
            } else {
                putExtra(NewPostActivity.ARG_POST_TITLE, "Re: " + getTitle());
            }
        }}, REQUEST_CODE_REPLY_THREAD);
    }

    public void showMenu(View view, final Post item) {
        PopupMenu popup = new PopupMenu(PostListActivity.this, view);
        Menu menu = popup.getMenu();
        popup.getMenuInflater().inflate(R.menu.menu_post_item, menu);

        boolean showLoadCommentMenu = mComments.get(item.id) != null &&
                mComments.get(item.id).size() >= COMMENT_PAGE_SIZE &&
                mComments.get(item.id).size() < mCommentCount.get(item.id);
        menu.findItem(R.id.action_more_comment).setVisible(showLoadCommentMenu);

        boolean showEditPostMenu = item.author.equals(Discuz.sUsername);
        menu.findItem(R.id.action_edit).setVisible(showEditPostMenu || Discuz.sIsModerator);
        menu.findItem(R.id.action_delete).setVisible(Discuz.sIsModerator);
        menu.findItem(R.id.action_warn).setVisible(Discuz.sIsModerator);
        menu.findItem(R.id.action_ban).setVisible(Discuz.sIsModerator);

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                int action = menuItem.getItemId();
                if (action == R.id.action_delete) {
                    if (item.number > 1) {
                        mSelectedPost = item.id;
                        moderateThread(R.layout.dialog_delete_post);
                    }
                    else
                        moderateThread(R.layout.dialog_delete_thread);
                }
                else if (action == R.id.action_warn) {
                    mSelectedPost = item.id;
                    moderateThread(R.layout.dialog_warn_post);
                }
                else if (action == R.id.action_ban) {
                    mSelectedPost = item.id;
                    moderateThread(R.layout.dialog_ban_post);
                }
                else if (action == R.id.action_edit) {
                    editPost(item);
                }
                else if (action == R.id.action_comment) {
                    addComment(item);
                }
                else if (action == R.id.action_more_comment) {
                    loadComment(item);
                }
                else if (action == R.id.action_quick_reply) {
                    quickReply(item);
                }
                else if (action == R.id.action_reply) {
                    gotoReply(item);
                }
                return true;
            }
        });
        popup.show();
    }

    public void showPollOptions() {
        final ListView listView = new ListView(this);
        listView.setChoiceMode(mMaxChoices > 1 ?
                AbsListView.CHOICE_MODE_MULTIPLE : AbsListView.CHOICE_MODE_SINGLE);
        int itemLayout = android.R.layout.simple_list_item_1;
        if (mAllowVote) itemLayout = mMaxChoices > 1 ?
                android.R.layout.simple_list_item_multiple_choice :
                android.R.layout.simple_list_item_single_choice;
        listView.setAdapter(new CommonListAdapter<PollOption>(mPollOptions, itemLayout) {
            @Override
            public void convertView(ViewHolder viewHolder, PollOption item) {
                ((TextView) viewHolder.getConvertView())
                        .setText(item.option + " (" + item.votes + "/" + item.percent + "%)");
            }
        });

        AccentAlertDialog.Builder builder = new AccentAlertDialog.Builder(PostListActivity.this)
                .setTitle(getString(R.string.diag_title_vote_result) +
                        " (" + String.format(getString(R.string.diag_title_max_choices), mMaxChoices) + ")")
                .setView(listView)
                .setNegativeButton(android.R.string.cancel, null);
        if (mAllowVote)
            builder.setPositiveButton(android.R.string.ok, null);

        mVoteDialog = builder.create();
        if (mAllowVote) mVoteDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                new DividerPainter(PostListActivity.this).paint(mVoteDialog.getWindow());
                mVoteDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                List<Integer> selected = new ArrayList<Integer>();
                                SparseBooleanArray checked = listView.getCheckedItemPositions();
                                for (int i = 0; i < checked.size(); i ++)
                                    if (checked.valueAt(i)) {
                                        int pos = checked.keyAt(i);
                                        if (pos < mPollOptions.size()) {
                                            int id = mPollOptions.get(pos).id;
                                            selected.add(id);
                                        }
                                    }
                                if (selected.size() <= mMaxChoices)
                                    doPollVote(selected);
                                else
                                    Helper.toast(String.format(getString(R.string.toast_too_many_votes), mMaxChoices));
                            }
                        });
            }
        });
        mVoteDialog.show();
    }

    public void showPayDialog() {
        final AlertDialog dialog = new AccentAlertDialog.Builder(this)
                .setTitle(R.string.buy_thread)
                .setMessage(String.format(getString(R.string.thread_price_info), mThreadPrice))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                new DividerPainter(PostListActivity.this).paint(dialog.getWindow());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                doBuyThread(dialog);
                            }
                        });
            }
        });
        dialog.show();
    }

    public void moderateThread(final int layout) {
        final View dialogView = View.inflate(this, layout, null);

        final DatePicker datePicker = ((DatePicker) dialogView.findViewById(R.id.date_expiration));
        if (datePicker != null)
            datePicker.setMinDate(System.currentTimeMillis() - 1000);

        final Switch displayDatepicker = ((Switch) dialogView.findViewById(R.id.display_datepicker));
        if (displayDatepicker != null) {
            datePicker.setVisibility(View.GONE);
            displayDatepicker.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    datePicker.setVisibility(displayDatepicker.isChecked() ? View.VISIBLE : View.GONE);
                }
            });
        }

        final Spinner moveToForum = ((Spinner) dialogView.findViewById(R.id.move_to));
        final Spinner threadTypes = ((Spinner) dialogView.findViewById(R.id.thread_type));
        if (moveToForum != null) Discuz.ForumThreadInfo.loadInfo(new Response.Listener<SparseArray<Discuz.ForumThreadInfo>>() {
            @Override
            public void onResponse(final SparseArray<Discuz.ForumThreadInfo> forumThreadInfo) {
                final List<String> list = new ArrayList<String>();
                int position = 0;
                for (int i = 0; i < forumThreadInfo.size(); i++) {
                    int fid = forumThreadInfo.keyAt(i);
                    list.add(forumThreadInfo.get(fid).name);
                    if (fid == mForumId)
                        position = list.size() - 1;
                }
                moveToForum.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                        int fid = forumThreadInfo.keyAt(i);
                        Discuz.ThreadTypes types = forumThreadInfo.get(fid).types;
                        List<String> list = new ArrayList<String>();
                        list.add(getString(R.string.string_uncategorized));
                        if (types != null) for (Map.Entry<String, Integer> e : types.entrySet())
                            list.add(e.getKey());
                        ArrayAdapter adapter = new ArrayAdapter<String>(PostListActivity.this,
                                android.R.layout.simple_spinner_item, list);
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                        threadTypes.setAdapter(adapter);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> adapterView) {
                    }
                });
                ArrayAdapter adapter = new ArrayAdapter<String>(PostListActivity.this,
                        android.R.layout.simple_spinner_item, list);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                moveToForum.setAdapter(adapter);
                moveToForum.setSelection(position);
            }
        });

        mThreadModerateDialog = new AccentAlertDialog.Builder(PostListActivity.this)
                .setTitle(R.string.action_moderate_thread)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mThreadModerateDialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                new DividerPainter(PostListActivity.this).paint(mThreadModerateDialog.getWindow());
                mThreadModerateDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                String reason = ((TextView)dialogView.findViewById(R.id.operate_reason))
                                        .getText().toString();
                                String expiration = "";
                                if (displayDatepicker != null && displayDatepicker.isChecked()) {
                                    Date date = new Date(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth());
                                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                                    dateFormat.setTimeZone(TimeZone.getDefault());
                                    expiration = dateFormat.format(date);
                                }
                                if (layout == R.layout.dialog_bump_thread) {
                                    String operation = ((Spinner)dialogView.findViewById(R.id.bump_or_down))
                                            .getSelectedItemPosition() == 0 ? "bump" : "down";
                                    doModerateThread(operation, reason);
                                }
                                else if (layout == R.layout.dialog_stick_thread) {
                                    int stickLevel = ((Spinner)dialogView.findViewById(R.id.stick_level))
                                            .getSelectedItemPosition();
                                    doModerateThread("stick", reason, stickLevel, expiration);
                                }
                                else if (layout == R.layout.dialog_highlight_thread) {
                                    int highlightColor = ((Spinner)dialogView.findViewById(R.id.highlight_color))
                                            .getSelectedItemPosition();
                                    doModerateThread("highlight", reason, highlightColor, expiration,
                                            ((CheckBox) dialogView.findViewById(R.id.highlight_bold)).isChecked() ? 1 : 0,
                                            ((CheckBox) dialogView.findViewById(R.id.highlight_italic)).isChecked() ? 1 : 0,
                                            ((CheckBox) dialogView.findViewById(R.id.highlight_underline)).isChecked() ? 1 : 0);
                                }
                                else if (layout == R.layout.dialog_digest_thread) {
                                    int digestLevel = ((Spinner)dialogView.findViewById(R.id.digest_level))
                                            .getSelectedItemPosition();
                                    doModerateThread("digest", reason, digestLevel, expiration);
                                }
                                else if (layout == R.layout.dialog_open_thread) {
                                    String operation = ((Spinner)dialogView.findViewById(R.id.bump_or_down))
                                            .getSelectedItemPosition() == 0 ? "open" : "close";
                                    doModerateThread(operation, reason);
                                }
                                else if (layout == R.layout.dialog_delete_thread) {
                                    doModerateThread("delete", reason);
                                }
                                else if (layout == R.layout.dialog_move_thread) {
                                    int i = moveToForum.getSelectedItemPosition();
                                    int fid = Discuz.ForumThreadInfo.getInfo().keyAt(i);
                                    String type = threadTypes.getSelectedItem().toString();
                                    Discuz.ThreadTypes types = Discuz.ForumThreadInfo.getInfo().get(fid).types;
                                    int typeId = types != null &&  types.containsKey(type) ? types.get(type) : 0;
                                    String move = ((Spinner)dialogView.findViewById(R.id.move_type))
                                            .getSelectedItemPosition() == 0 ? "normal" : "redirect";
                                    doModerateThread("move", reason, fid, typeId, move);
                                }
                                else if (layout == R.layout.dialog_delete_post) {
                                    doModerateThread("delpost", reason, mSelectedPost);
                                }
                                else if (layout == R.layout.dialog_warn_post) {
                                    doModerateThread("warn", reason, mSelectedPost,
                                            ((Spinner)dialogView.findViewById(R.id.warn_or_not)).getSelectedItemPosition() == 0 ? 1 : 0);
                                }
                                else if (layout == R.layout.dialog_ban_post) {
                                    doModerateThread("banpost", reason, mSelectedPost,
                                            ((Spinner)dialogView.findViewById(R.id.ban_or_not)).getSelectedItemPosition() == 0 ? 1 : 0);
                                }
                                else {
                                    mThreadModerateDialog.dismiss();
                                }
                            }
                        });
            }
        });
        mThreadModerateDialog.show();
    }

    public void showModerateOptions() {
        final ListView listView = new ListView(this);
        final AlertDialog dialog =  new AccentAlertDialog.Builder(PostListActivity.this)
                .setTitle(R.string.action_moderate_thread)
                .setView(listView)
                .create();

        // Note: these layouts correspond to moderate_options
        final int[] layouts = {
                R.layout.dialog_bump_thread,
                R.layout.dialog_stick_thread,
                R.layout.dialog_highlight_thread,
                R.layout.dialog_digest_thread,
                R.layout.dialog_open_thread,
                R.layout.dialog_move_thread,
                R.layout.dialog_delete_thread,
        };
        listView.setAdapter(ArrayAdapter.createFromResource(this,
                R.array.moderate_options, android.R.layout.simple_list_item_1));

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                if (i >= 0 && i < layouts.length)
                    moderateThread(layouts[i]);
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    static Pattern msgPathPattern = Pattern.compile("<img[^>]* file=\"(.*?)\"");
    static CallbackMatcher msgMatcher = new CallbackMatcher("<ignore_js_op>(.*?)</ignore_js_op>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // this function compiles the message to display in android TextViews
    static String compileMessage(String message, final Map<String, Attachment> attachments, final String size) {
        message = msgMatcher.replaceMatches(message, new CallbackMatcher.Callback() {
            @Override
            public String foundMatch(MatchResult matchResult) {
                Matcher pathMatcher = msgPathPattern.matcher(matchResult.group(1));
                if (pathMatcher.find()) {
                    String src = pathMatcher.group(1);
                    Attachment attachment = attachments.get(src);
                    if (attachment != null) {
                        String url = Discuz.getAttachmentThumb(attachment.id, size);
                        attachments.put(url, attachment);
                        return "<img src=\"" + url + "\" />";
                    }
                }
                Log.w(PostListActivity.class.toString(),
                        "attachment image not found (" + matchResult.group(1) + ")");
                return "";
            }
        });

        message = message.replaceAll(" file=\"(.*?)\"", " src=\"$1\"");
        message = message.replaceAll("<script[^>]*>(.*?)</script>", "");

        return message;
    }

    private void setupListFragment() {
        if (mListFragment == null) {

            mListFragment = CommonListFragment.getNewFragment(
                    Post.class,
                    R.layout.fragment_simple_list,
                    R.layout.fragment_post_item,
                    R.id.list);

            mListFragment.setOnScrollListener(new AbsListView.OnScrollListener() {
                private int mCurrentItem;
                @Override
                public void onScrollStateChanged(AbsListView absListView, int i) {
                    if (i == AbsListView.OnScrollListener.SCROLL_STATE_IDLE && mListPages > 1) {
                        ActionBar actionBar = getActionBar();
                        Intent intent = getIntent();
                        int pageOffset = intent.getIntExtra("page", 0);
                        int pageScroll = mCurrentItem / PAGE_SIZE_COUNT + pageOffset;
                        if (actionBar != null && actionBar.getSelectedNavigationIndex() != pageScroll) {
                            intent.putExtra("update-nav-spinner", true);
                            actionBar.setSelectedNavigationItem(pageScroll);
                        }
                    }
                }

                @Override
                public void onScroll(AbsListView absListView, int i, int i2, int i3) {
                    mCurrentItem = i;
                }
            });

        }

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, mListFragment)
                // don't use commit()
                .commitAllowingStateLoss();
    }

    private void destroyListFragment() {
        if (mListFragment != null) try{
            getSupportFragmentManager().beginTransaction()
                    .remove(mListFragment)
                    .commit();
            ((BitmapLruCache) mImageCache.images).evictAll();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_framelayout);
        if (getActionBar() != null)
            getActionBar().setDisplayHomeAsUpEnabled(true);

        String title = getIntent().getStringExtra("title");
        if (title != null) setTitle(title);

        setupListFragment();
    }

    @Override
    protected void onPause() {
        isPaused = true;
        // remove fragment to recycle memory
        if (PostListActivity.this.mPrefMaxImageSize.x >= 0)
            // remove fragment after some milliseconds to avoid splash
            new android.os.Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (PostListActivity.this.isPaused)
                        destroyListFragment();
                }
            }, 500);
        super.onPause();
    }

    @Override
    protected void onResume() {
        isPaused = false;
        setupListFragment();
        super.onResume();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_CODE_EDIT_POST) {
            if (resultCode > 0) mListFragment.reloadAll();
        }
        else if (requestCode == REQUEST_CODE_REPLY_THREAD) {
            if (resultCode > 0) mListFragment.reloadLast();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_post_list, menu);

        menu.findItem(R.id.action_moderate_thread).setVisible(Discuz.sIsModerator);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_quick_reply) {
            quickReply(null);
            return true;
        }
        else if (id == R.id.action_reply) {
            gotoReply(null);
            return true;
        }
        else if (id == R.id.action_mark_fav) {
            doMarkFavourite();
            return true;
        }
        else if (id == R.id.action_reverse_order) {
            Intent intent = getIntent();
            intent.putExtra("page", 0);
            intent.putExtra("reverse", !intent.getBooleanExtra("reverse", false));
            mListFragment.reloadAll();
            return true;
        }
        else if (id == R.id.action_see_author) {
            Intent intent = getIntent();
            int authorId = intent.getIntExtra("authorid", 0);
            intent.putExtra("page", 0);
            intent.putExtra("authorid", authorId > 0 ? 0 : mAuthorId);
            mListFragment.reloadAll();
            return true;
        }
        else if (id == R.id.action_share) {
            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, getTitle() + "\n" + Discuz.DISCUZ_URL +
                    "forum.php?mod=viewthread&tid=" + getIntent().getIntExtra("tid", 0));
            intent.setType("text/plain");
            startActivity(intent);
            return true;
        }
        else if (id == R.id.action_goto_forum) {
            if (mForumId > 0 && getIntent().getIntExtra("fid", 0) != mForumId)
                startActivity(new Intent(this, ThreadListActivity.class) {{
                    putExtra("fid", mForumId);
                }});
            finish();
            return true;
        }
        else if (id == R.id.action_moderate_thread) {
            showModerateOptions();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private Pattern patt1 = Pattern.compile("<span style=\"display:none\">.*?</span>", Pattern.DOTALL);
    private Pattern patt2 = Pattern.compile("<.+quote>.+div>", Pattern.DOTALL);
    private Pattern patt3 = Pattern.compile("<[^<>]*>", Pattern.DOTALL);
    private String getTrimstr(Post post) {
        // Note: see Discuz source net/discuz/source/PostSender.java
        int tid = getIntent().getIntExtra("tid", 0);
        String message = post.message;
        message = patt1.matcher(message).replaceAll("");
        message = Html.fromHtml(message).toString();
        message = patt2.matcher(message).replaceAll("");
        message = patt3.matcher(message).replaceAll("");
        if (message.length() > MAX_TRIMSTR_LENGTH)
            message = message.substring(0, MAX_TRIMSTR_LENGTH - 3) + "...";
        return "[quote]"+
            "[size=2]"+
                "[color=#999999]"+post.author+" at "+Html.fromHtml(post.dateline)+"[/color] "+
                "[url=forum.php?mod=redirect&goto=findpost&pid="+post.id+"&ptid="+tid+
                    "][img]static/image/common/back.gif[/img][/url]"+
            "[/size]\n"+
            message+
        "[/quote]";
    }

    // Note: must put huge objects in fragment scope!
    final HtmlImageGetter.HtmlImageCache mImageCache =
            new HtmlImageGetter.HtmlImageCache(new BitmapLruCache());
    final SparseArray<List<View>> mCommentCache = new SparseArray<List<View>>();

    @Override
    public CommonListAdapter getListViewAdaptor(CommonListFragment fragment) {
        return new CommonListAdapter<Post>() {

            @Override
            public void convertView(ViewHolder viewHolder, final Post item) {
                String avatar_url = Discuz.DISCUZ_URL +
                        "uc_server/avatar.php?uid="+item.authorId+"&size=small";
                NetworkImageView avatar = (NetworkImageView) viewHolder.getView(R.id.avatar);
                avatar.setImageUrl(avatar_url, ThisApp.imageLoader);
                avatar.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startActivity(new Intent(PostListActivity.this, UserProfileActivity.class) {{
                            putExtra("uid", item.authorId);
                        }});
                    }
                });

                viewHolder.setText(R.id.author, item.author);
                viewHolder.setText(R.id.date, Html.fromHtml(item.dateline));
                viewHolder.setText(R.id.index, "#"+item.number);

                viewHolder.getView(R.id.menu).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showMenu(view, item);
                    }
                });

                TextView messageText = (TextView) viewHolder.getView(R.id.message);
                messageText.setTextSize(mPrefFontSize);
                Spannable messageContent = (Spannable) Html.fromHtml(item.message,
                        new HtmlImageGetter(messageText, mImageCache, mPrefMaxImageSize), null);
                messageContent = (Spannable) Helper.setSpanClickListener(messageContent,
                        URLSpan.class,
                        new Helper.OnSpanClickListener() {
                            @Override
                            public boolean onClick(View widget, String data) {
                                // TODO: complete these actions
                                final Uri uri = Uri.parse(data);
                                String mod = uri.getQueryParameter("mod");
                                if ("viewthread".equals(mod)) {
                                    startActivity(new Intent(PostListActivity.this, PostListActivity.class) {{
                                        putExtra("tid", Helper.toSafeInteger(uri.getQueryParameter("tid"), 0));
                                    }});
                                    return true;
                                }
                                else if ("post".equals(mod)) {
                                    if ("reply".equals(uri.getQueryParameter("action"))) {
                                        gotoReply(null);
                                        return true;
                                    }
                                }
                                else if ("forumdisplay".equals(mod)) {
                                    startActivity(new Intent(PostListActivity.this, ThreadListActivity.class) {{
                                        putExtra("fid", Helper.toSafeInteger(uri.getQueryParameter("fid"), 0));
                                    }});
                                    return true;
                                }
                                return false;
                            }
                        });
                messageContent = (Spannable) Helper.setSpanClickListener(messageContent,
                        ImageSpan.class,
                        new Helper.OnSpanClickListener() {
                            @Override
                            public boolean onClick(View widget, String src) {
                                Intent oldInt = getIntent();
                                Intent intent = new Intent(ThisApp.context, AttachmentViewer.class);
                                intent.putExtra("tid", oldInt.getIntExtra("tid", 0));
                                int offset = oldInt.getIntExtra("page", 0) * PAGE_SIZE_COUNT;
                                intent.putExtra("index", offset + mListFragment.getIndex(item));
                                intent.putExtra("reverse", oldInt.getBooleanExtra("reverse", false));
                                intent.putExtra("authorid", oldInt.getIntExtra("authorid", 0));

                                Attachment attachment = mAttachmentMap.get(src);
                                // attachment image
                                if (attachment != null) {
                                    intent.putExtra("src", attachment.src);
                                    startActivity(intent);
                                }
                                // external images
                                else if (!Discuz.getSafeUrl(src).startsWith(Discuz.DISCUZ_HOST)) {
                                    intent.putExtra("src", src);
                                    startActivity(intent);
                                }
                                return false;
                            }
                        });

                messageText.setText(messageContent);
                messageText.setMovementMethod(LinkMovementMethod.getInstance());

                // see #67
                // REF: http://blog.csdn.net/jaycee110905/article/details/8762274
                messageText.setFocusableInTouchMode(true);
                messageText.setFocusable(true);
                messageText.setClickable(true);
                messageText.setLongClickable(true);

                // load comments
                LinearLayout commentList = (LinearLayout) viewHolder.getView(R.id.comment_list);
                commentList.removeAllViews();
                List<View> cachedViews = mCommentCache.get(item.id);
                if (cachedViews == null) {
                    cachedViews = new ArrayList<View>();
                    mCommentCache.put(item.id, cachedViews);
                }
                List<Comment> comments = mComments.get(item.id);
                if (comments != null) {
                    for (int i = 0; i < comments.size(); i ++) {
                        Comment comment = comments.get(i);
                        View commentView = null;
                        // First try to get commentView from cache
                        if (i < cachedViews.size()) {
                            commentView = cachedViews.get(i);
                            if (commentView.getParent() != null)
                                ((ViewGroup) commentView.getParent()).removeView(commentView);
                        }
                        // Note: removeView() is not working sometimes, so we may have to recreate one
                        // see nsdn bug #448d2
                        if (commentView == null || commentView.getParent() != null) {
                            commentView = new TextView(PostListActivity.this);
                            commentView.setPadding(32, 0, 0, 0);
                            cachedViews.add(commentView);
                        }
                        ((TextView) commentView).setText(
                                Html.fromHtml("<b>" + comment.author + "</b>&nbsp;&nbsp;" + comment.comment));
                        commentList.addView(commentView);
                    }
                }

                // show attachments
                TextView attachments = (TextView) viewHolder.getView(R.id.attachment_list);
                Helper.updateVisibility(attachments, item.attachments.size() > 0);
                attachments.setText(getString(R.string.text_view_attachments) +
                        " (" + item.attachments.size() + ")");
                final int offset = getIntent().getIntExtra("page", 0) * PAGE_SIZE_COUNT;
                attachments.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        final Intent oldInt = getIntent();
                        startActivity(new Intent(ThisApp.context, AttachmentViewer.class) {{
                            putExtra("tid", oldInt.getIntExtra("tid", 0));
                            putExtra("index", offset + mListFragment.getIndex(item));
                            putExtra("reverse", oldInt.getBooleanExtra("reverse", false));
                            putExtra("authorid", oldInt.getIntExtra("authorid", 0));
                        }});
                    }
                });

                // show votes
                TextView votes = (TextView) viewHolder.getView(R.id.votes);
                Helper.updateVisibility(votes, false);
                if (item.number == 1 && mPollOptions.size() > 0) {
                    Helper.updateVisibility(votes, true);
                    votes.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            showPollOptions();
                        }
                    });
                }

                // buy thread
                TextView buy = (TextView) viewHolder.getView(R.id.pay);
                Helper.updateVisibility(buy, false);
                if (item.number == 1 && mThreadPrice > 0) {
                    Helper.updateVisibility(buy, true);
                    buy.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            showPayDialog();
                        }
                    });
                }
            }
        };
    }

    @Override
    public void onItemClick(CommonListFragment fragment, View view, int position, long id) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onLoadingMore(CommonListFragment fragment, final List listData) {
        loadDisplayPreference();

        final int pageOffset = getIntent().getIntExtra("page", 0);
        final int page = listData.size() / PAGE_SIZE_COUNT;
        Discuz.execute("viewthread", new HashMap<String, Object>() {{
            put("tid", getIntent().getIntExtra("tid", 0));
            put("ppp", PAGE_SIZE_COUNT);
            put("page", page + pageOffset + 1);

            Intent intent = getIntent();
            int authorId = intent.getIntExtra("authorid", 0);
            if (authorId > 0)
                put("authorid", authorId);
            if (intent.getBooleanExtra("reverse", false))
                put("ordertype", 1);
        }}, null, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject data) {
                int total = -1;
                if (PostListActivity.this.isFinishing()) {
                    Log.w(PostListActivity.class.toString(), "activity is finished.");
                }
                else if (data.has(Discuz.VOLLEY_ERROR)) {
                    Helper.toast(R.string.network_error_toast);
                }
                else if (data.opt("Message") instanceof JSONObject) {
                    try {
                        JSONObject message = data.getJSONObject("Message");
                        listData.clear();
                        new AccentAlertDialog.Builder(PostListActivity.this)
                                .setTitle(R.string.there_is_something_wrong)
                                .setMessage(message.getString("messagestr"))
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        finish();
                                    }
                                })
                                .show();
                        total = 0;
                    }
                    catch (JSONException e) {
                        Log.e(PostListActivity.class.toString(),
                                "JsonError: Load Post List Failed (" + e.getMessage() + ")");
                        Helper.toast(R.string.load_failed_toast);
                    }
                }
                else {
                    Helper.setListLength(listData, page * PAGE_SIZE_COUNT);
                    try {
                        JSONObject var = data.getJSONObject("Variables");

                        JSONArray postlist = var.getJSONArray("postlist");
                        for (int i = 0; i < postlist.length(); i ++) {
                            JSONObject postData = postlist.getJSONObject(i);
                            Post post = new Post(postData);
                            for (Attachment attachment : post.attachments)
                                mAttachmentMap.put(attachment.src, attachment);
                            post.message = compileMessage(post.message, mAttachmentMap,
                                    // TODO: add more image size here (see forumimage.php
                                    mPrefMaxImageSize.x < 0 ? "" : "268x380");
                            listData.add(post);
                        }

                        // Note: in x2 there is only "replies"
                        JSONObject thread = var.getJSONObject("thread");
                        setTitle(thread.getString("subject"));
                        mAuthorId = Helper.toSafeInteger(thread.optString("authorid"), 0);
                        int replies = Integer.parseInt(thread.has("replies") ?
                                thread.getString("replies") : thread.getString("allreplies"));
                        // get thread price
                        mThreadPrice = var.getBoolean("forum_threadpay") ?
                                Helper.toSafeInteger(thread.optString("price"), 0) : 0;
                        // setup action bar only once when loading items
                        if (page == 0)
                            setupActionBarPages(replies / PAGE_SIZE_COUNT + 1);
                        // must subtract items by the page offset
                        total = replies + 1 - pageOffset * PAGE_SIZE_COUNT;

                        // comments
                        if (var.opt("comments") instanceof JSONObject) {
                            JSONObject comments = var.getJSONObject("comments");
                            for(Iterator<String> iter = comments.keys(); iter.hasNext(); ) {
                                String key = iter.next();
                                JSONArray commentListData = comments.getJSONArray(key);
                                int pid = Integer.parseInt(key);
                                List<Comment> commentList = new ArrayList<Comment>();
                                for (int i = 0; i < commentListData.length(); i ++) {
                                    JSONObject commentData = commentListData.getJSONObject(i);
                                    commentList.add(new Comment(commentData));
                                }
                                mComments.put(pid, commentList);
                                mCommentCount.put(pid, Integer.MAX_VALUE);
                            }
                        }

                        // forum
                        if (var.has("fid"))
                            mForumId = Helper.toSafeInteger(var.optString("fid"), 0);
                        else if (var.opt("forum") instanceof JSONObject)
                            mForumId = Helper.toSafeInteger(var.optJSONObject("forum").optString("fid"), 0);

                        // votes
                        if (var.has("special_poll")) {
                            JSONObject poll = var.getJSONObject("special_poll");
                            mPollOptions.clear();
                            JSONObject polloptions = poll.getJSONObject("polloptions");
                            for (Iterator<String> iter = polloptions.keys(); iter.hasNext(); ) {
                                String key = iter.next();
                                mPollOptions.add(new PollOption(polloptions.getJSONObject(key)));
                            }
                            mAllowVote = poll.getBoolean("allowvote");
                            mMaxChoices = Math.max(Helper.toSafeInteger(poll.getString("maxchoices"), 1), 1);
                        }

                    } catch (JSONException e) {
                        Log.e(PostListActivity.class.toString(),
                                "JsonError: Load Post List Failed (" + e.getMessage() + ")");
                        Helper.toast(R.string.load_failed_toast);
                    }
                }
                mListFragment.loadMoreDone(total);
            }
        });
    }
}
