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

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

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
		System.out.println("All systems are go!");

		for (;;) {
			WatchKey key;

			try {
				key = watcher.take();
			} catch (InterruptedException x) {
				System.out.println(x.getStackTrace());
				return;
			}

			Path dir = keys.get(key);
			if (dir == null) {
				System.err.println("WatchKey not recognized!!");
				continue;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				Kind<?> kind = event.kind();

				if (kind == OVERFLOW) {
					System.out
							.println("Event overflow detected. Some changes might have been not noticed. "
									+ "Usually this doesn't mean there's a problem, "
									+ "but please do check that the encryption was succesfully completed.");
					continue;
				}

				// Context for directory entry event is the file name of entry
				WatchEvent<Path> ev = cast(event);
				Path name = ev.context();
				Path pathToFile = dir.resolve(name);

				// print out event
				System.out.format("%s: %s\n", event.kind().name(), pathToFile);
				if (kind == ENTRY_CREATE) {

					// Check if already encrypted
					String extension = "";
					int i = pathToFile.toString().lastIndexOf('.');
					if (i > 0) {
						extension = pathToFile.toString().substring(i + 1);
					}

					if (!extension.equals("axx")) {
						// try executing axcrypt for watched directory
						try {
							System.out.println("Name: " + name);
							System.out.println("Child: " + pathToFile);
							encrypt(pathToFile);
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}

			}

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				keys.remove(key);

				// all directories are inaccessible
				if (keys.isEmpty()) {
					break;
				}
			}

		}
	}

	private void encrypt(Path pathToFile) throws IOException {
		String remote = remoteDir.toString();
		String dir = directory.toString();
		System.out.println("trying axcrypt, remote is: " + remote);
		System.out.println("directory is: " + dir);
		System.out.println("path to file is: " + pathToFile);

		Process axCryptProcess = Runtime.getRuntime().exec(
				"C:\\Program Files\\Axantum\\Axcrypt\\AxCrypt -b 2 -e -k \"testi\" -z "
						+ pathToFile);
		InputStream rdiffStream = axCryptProcess.getInputStream();
		Reader reader = new InputStreamReader(rdiffStream);
		BufferedReader bReader = new BufferedReader(reader);
		String nextLine = null;
		while ((nextLine = bReader.readLine()) != null) {
			System.out.println(nextLine);
		}
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
