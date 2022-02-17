import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class TwitchClient {
    final String clientID, accessToken;
    private static final Pattern accessTokenFinder = Pattern.compile("(?<=\"access_token\":\").*?(?=\")");

    public TwitchClient(String clientID, String clientSecret) {
        assert clientID != null && clientSecret != null;
        this.clientID = clientID;
        String accessTokenPage;
        try {
            accessTokenPage = getPage("POST", "https://id.twitch.tv/oauth2/token?grant_type=client_credentials&client_secret=" + clientSecret);
        } catch (IOException e) {
            throw new IllegalArgumentException("'https://id.twitch.tv' is blocked or client ID/Secret is invalid'");
        }
        accessToken = getPatternResults(accessTokenFinder, accessTokenPage)[0];
    }

    static String[] getPatternResults(Pattern query, String string) {
        return query.matcher(string).results().map(MatchResult::group).toArray(String[]::new);
    }

    String getPage(String requestMethod, String spec) throws IOException {
      return getPage(requestMethod, spec, true);
    }

    String getPage(String requestMethod, String spec, boolean send_client_id) throws IOException {
        HttpURLConnection urlConnection = (HttpURLConnection) new URL(spec).openConnection();
        urlConnection.setRequestMethod(requestMethod);
        if (send_client_id) {
          urlConnection.addRequestProperty("Client-ID", clientID);
        }
        if (accessToken != null) {
            urlConnection.addRequestProperty("Authorization", "Bearer " + accessToken);
        }
        return new String(urlConnection.getInputStream().readAllBytes());
    }
}

class TwitchUser {
    private static final Pattern
            idFinder = Pattern.compile("(?<=\"id\":\").*?(?=\")"),
            clipURLFinder = Pattern.compile("(?<=\"url\":\").*?(?=\")"),
            clipTitleFinder = Pattern.compile("(?<=\"title\":\").*?(?=\")"),
            clipCursorFinder = Pattern.compile("(?<=\"cursor\":\").*?(?=\")"),
            createdAtFinder = Pattern.compile("(?<=\"created_at\":\").*?(?=\")"),
            viewCountFinder = Pattern.compile("(?<=\"view_count\":).*?(?=,)");
    private static final SimpleDateFormat rfc339 = new SimpleDateFormat("yyyy-MM-dd");
    private final String name;
    private final int id;
    private final TwitchClient client;
    private final Date createdAt;

    public TwitchUser(String name, TwitchClient client) {
        assert client != null;
        this.client = client;
        this.name = name;
        try {
            String idPage = client.getPage("GET", "https://api.twitch.tv/helix/users?login=" + name);
            String[] rawID = TwitchClient.getPatternResults(idFinder, idPage);
            if (rawID.length == 0) {
                throw new IllegalArgumentException("\033[3m" + name + "\033[0m could not be found");
            }
            String isoDate = TwitchClient.getPatternResults(createdAtFinder, idPage)[0];
            createdAt = rfc339.parse(isoDate.substring(0, isoDate.length() - 10));

            id = Integer.parseInt(rawID[0]);
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            throw new IllegalArgumentException("'https://api.twitch.tv' is blocked or client ID/Secret is invalid'");
        }
    }

    @Override
    public String toString() {
        return name;
    }

    void clips(String filter) {
        clips(Pattern.compile("(?i)(" + filter + ')'));
    }

    public void clips() {
        clips(TwitchVOD.alwaysTrue);
    }

    public void clips(Pattern filter) {
        try {
            Calendar calendar = Calendar.getInstance();
            Calendar separated = (Calendar) calendar.clone();
            String cursor;

            while (calendar.getTime().compareTo(createdAt) >= 0) {
                cursor = "";
                calendar.add(Calendar.MONTH, -1);
                String page;
                while (cursor != null) {
                    page = client.getPage("GET", "https://api.twitch.tv/helix/clips" +
                            "?broadcaster_id=" + id +
                            "&first=100&after=" + cursor +
                            "&started_at=" + rfc339.format(calendar.getTime()) + "-00:00&" +
                            "&ended_at=" + rfc339.format(separated.getTime()) + "-00:00");
                    String[] cursorResults = TwitchClient.getPatternResults(clipCursorFinder, page);
                    cursor = cursorResults.length > 0 ? cursorResults[0] : null;
                    String[] urls = TwitchClient.getPatternResults(clipURLFinder, page);
                    String[] viewCount = TwitchClient.getPatternResults(viewCountFinder, page);
                    String[] titles = TwitchClient.getPatternResults(clipTitleFinder, page);
                    for (int i = 0; i < Math.min(urls.length, titles.length); i++) {
                        if (filter.matcher(titles[i]).find()) {
                            System.out.println(titles[i] + " @ " + viewCount[i] + " views" + ": " + urls[i]);
                        }
                    }
                }
                separated.add(Calendar.MONTH, -1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public TwitchVOD[] vods() {
        try {
            String vodPage = client.getPage("GET", "https://api.twitch.tv/helix/videos?user_id=" + id + "&first=100");
            String[] vodIDsclient = TwitchClient.getPatternResults(idFinder, vodPage);
            TwitchVOD[] vods = new TwitchVOD[vodIDsclient.length];
            for (int i = 0; i < vods.length; i++) {
                vods[i] = new TwitchVOD(Integer.parseInt(vodIDsclient[i]), client);
            }
            return vods;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid User!");
        }
    }

}

class TwitchVOD {
    private static final Pattern
            contentOffsetSeconds = Pattern.compile("(?<=\"content_offset_seconds\":).*?(?=,)"),
            nameFinder = Pattern.compile("(?<=\"display_name\":\").*?(?=\")"),
            bodyFinder = Pattern.compile("(?<=\"body\":\").*?(?=\")"),
            titleFinder = Pattern.compile("(?<=\"title\":\").*?(?=\")"),
            vodLinkFinder = Pattern.compile("(?<=\"animated_preview_url\":\").*?(?=storyboards)"),
            broadcastTypeFinder = Pattern.compile("(?<=\"type\":\").*?(?=\")");
    static final Pattern
            alwaysTrue = Pattern.compile("(?s).*"),
            cursorFinder = Pattern.compile("(?<=\"_next\":\").*?(?=\")");

    enum Type {ARCHIVE, HIGHLIGHT, UNKNOWN}

    private final int id;
    private final String title;
    private final TwitchClient client;
    private final Type vodType;

    public TwitchVOD(int id, TwitchClient client) {
        this.id = id;
        this.client = client;
        try {
            String vodJSON = client.getPage("GET", "https://api.twitch.tv/helix/videos?id=" + id);
            title = TwitchClient.getPatternResults(titleFinder, vodJSON)[0];
            switch (TwitchClient.getPatternResults(broadcastTypeFinder, vodJSON)[0]) {
                case "archive":
                    vodType = Type.ARCHIVE;
                    break;
                case "highlight":
                    vodType = Type.HIGHLIGHT;
                    break;
                default:
                    vodType = Type.UNKNOWN;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Video ID: " + id + " is invalid");
        }
    }

    public int getId() {
        return id;
    }

    private String convertImgToM3U8(String imgLink) {
        imgLink += "chunked/";
        switch (vodType) {
            case ARCHIVE:
                imgLink += "index-dvr.m3u8";
                break;
            case HIGHLIGHT:
                imgLink += "highlight-" + id + ".m3u8";
                break;
            default:
                imgLink = null;
        }
        return imgLink;
    }

    public String m3u8() {
        try {
            String vodJSON = client.getPage("GET", "https://api.twitch.tv/helix/videos?id=" + id);
            String[] data = TwitchClient.getPatternResults(vodLinkFinder, vodJSON);
            return data.length > 0 ? convertImgToM3U8(data[0]) : null;
        } catch (IOException e) {
            throw new IllegalArgumentException("Video ID: " + id + " is invalid");
        }
    }

    @Override
    public String toString() {
        return title;
    }

    public void comments(boolean announce, Pattern filter) {
        String cursor = "";
        while (cursor != null) {
            String vodJSON;
            try {
                vodJSON = client.getPage("GET", "https://api.twitch.tv/v5/videos/" + id + "/comments?cursor=" + cursor + "&client_id=kimne78kx3ncx6brgo4mv6wki5h1ko", false);
            } catch (IOException e) {
                throw new IllegalArgumentException("Video ID: " + id + " is invalid");
            }
            String[] patternResults = TwitchClient.getPatternResults(cursorFinder, vodJSON);
            cursor = patternResults.length > 0 ? patternResults[0] : null;

            String[]
                    timestamps = TwitchClient.getPatternResults(contentOffsetSeconds, vodJSON),
                    names = TwitchClient.getPatternResults(nameFinder, vodJSON),
                    messages = TwitchClient.getPatternResults(bodyFinder, vodJSON);
            int length = Math.min(timestamps.length, Math.min(names.length, messages.length));
            for (int i = 0; i < length; i++) {
                int seconds = (int) Float.parseFloat(timestamps[i]);
                String body = messages[i];
                if (filter.matcher(body).find()) {
                    String time = String.format("%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60);
                    String username = names[i];
                    String comment = "[" + time + "][" + username + "]:" + body;
                    if (announce) {
                        System.out.println(comment);
                    }
                }
            }
        }
    }
}