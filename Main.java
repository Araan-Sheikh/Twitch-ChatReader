import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        AtomicReference<TwitchClient> client = new AtomicReference<>();
        Thread clientAccessThread = new Thread(() -> client.set(new TwitchClient("w92meanbt3g3xeerqixkngka4g5wsb", "ramwfbblieujqdk5fzwz1qm7zrhqgh")));
        clientAccessThread.start();

        Scanner input = new Scanner(System.in);
        System.out.print("Would you like to search all chats on a Channel, individual VOD, or for clip titles? (channel/vod/clip) >>> ");
        String response = input.next();
        int videoID = -1;
        AtomicReference<TwitchVOD[]> vods = new AtomicReference<>();
        Thread userThread = null;
        if (response.toLowerCase().contains("channel")) {
            System.out.print("Please enter a channel name >>> ");
            String streamerName = input.next();
            userThread = new Thread(() -> {
                try {
                    clientAccessThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                TwitchUser user = new TwitchUser(streamerName, client.get());
                vods.set(user.vods());
            });
            userThread.start();
        } else if (response.toLowerCase().contains("vod")) {
            System.out.print("Enter a VOD ID: ");
            videoID = input.nextInt();
        } else if (response.toLowerCase().contains("clip")) {
            System.out.print("Please enter a channel name >>> ");
            String streamerName = input.next();
            clientAccessThread.join();
            TwitchUser user = new TwitchUser(streamerName, client.get());
            input.nextLine();
            user.clips(getFilter(input));
            System.exit(0);
        }
        input.nextLine();
        Pattern filter = getFilter(input);
        if (userThread != null) {
            userThread.join();
            for (TwitchVOD vod : vods.get()) {
                System.out.println("\nWorking on " + vod.getId());
                new Thread(() -> System.out.println(vod.m3u8())).start();
                vod.comments(true, filter);
            }
        } else {
            clientAccessThread.join();
            TwitchVOD vod = new TwitchVOD(videoID, client.get());
            System.out.println(vod.m3u8());
            vod.comments(true, filter);
        }
    }

    private static Pattern getFilter(Scanner input) {
        Pattern filter;
        System.out.print("(REGEXP) Please type in any keywords you would like to look for (separated by a | (NOT the letter l or i)), if none, leave this blank\nExamples: 'hello' 'hello|world'\n>>> ");
        String string = input.nextLine();
        if (string.isBlank()) {
            filter = TwitchVOD.alwaysTrue;
        } else {
            filter = Pattern.compile("(?i)(" + string + ')');
        }
        return filter;
    }

    public static void main2(String[] args) {
        TwitchClient twitchClient = new TwitchClient("cuwhphy3xzy01xn60rddmr57x8hzc6", "9milc7hacuyl8eg5cdpgllbdqpze9u");
        TwitchUser user = new TwitchUser("joplaysviolin", twitchClient);
        user.clips("burp|belch");
    }
}
