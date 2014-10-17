import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;

import static java.nio.file.StandardWatchEventKinds.*;
import static java.nio.file.LinkOption.*;

import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;

public class AutoEncryptor {
	private WatchService watcher;
	private Map<WatchKey, Path> keys;
	private boolean trace = false;
	private Path remoteDir;
	private Path directory; // TODO this is for testing, use directory registry!

	public AutoEncryptor(Path dir, Path remoteDir) throws IOException {
		this.watcher = FileSystems.getDefault().newWatchService();
		this.keys = new HashMap<WatchKey, Path>();
		this.remoteDir = remoteDir;
		this.directory = dir;

		register(dir);

		// enable trace after initial registration
		this.trace = true;
	}

	private void register(Path dir) throws IOException {
		// TODO Auto-generated method stub
		WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE,
				ENTRY_MODIFY);
		if (trace) {
			Path prev = keys.get(key);
			if (prev == null) {
				System.out.format("register: %s\n", dir);
			} else {
				if (!dir.equals(prev)) {
					System.out.format("update: %s -> %s\n", prev, dir);
				}
			}
		}
		keys.put(key, dir);
	}

	private void processEvents() {
		// TODO Auto-generated method stub
		System.out.println("test");
	}

	public static void usage() {
		System.out.println("Usage information:");
	}

	public static void main(String[] args) throws IOException {
		// parse arguments
		if (args.length != 2)
			usage();

		// register directory and remote and process its events
		Path dir = Paths.get(args[0]);
		Path remoteDir = Paths.get(args[1]);
		new AutoEncryptor(dir, remoteDir).processEvents();
	}

}
