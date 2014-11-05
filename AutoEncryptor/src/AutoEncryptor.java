import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.*;
import java.util.*;

public class AutoEncryptor {
	private WatchService watcher;
	private Map<WatchKey, Path> keys;
	private boolean trace = false;
	private Path remoteDir;

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

	public AutoEncryptor(Path dir, Path remoteDir) throws IOException {
		this.watcher = FileSystems.getDefault().newWatchService();
		this.keys = new HashMap<WatchKey, Path>();
		this.remoteDir = remoteDir;

		register(dir);

		// enable trace after initial registration
		this.trace = true;
	}

	private void register(Path dir) throws IOException {
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
				printErrorMessage(Error.DIR_NULL);
				continue;
			}

			for (WatchEvent<?> event : key.pollEvents()) {
				Kind<?> kind = event.kind();

				if (kind == OVERFLOW) {
					printErrorMessage(Error.OVERFLOW);
					continue;
				}

				WatchEvent<Path> ev = cast(event);
				Path name = ev.context();
				Path pathToFile = dir.resolve(name);

				System.out.format("%s: %s\n", event.kind().name(), pathToFile);
				if (kind == ENTRY_CREATE) {
					String extension = getExtensionFromPath(pathToFile);
					if (!extension.equals("axx")) {
						try {
							Path encrypted = encrypt(pathToFile);
							Path newLocation = parseNewLocation(encrypted);
							move(encrypted, newLocation);
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							break;
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

	private void move(Path encrypted, Path newLocation) {
		// TODO Auto-generated method stub
		
	}

	private Path parseNewLocation(Path encrypted) {
		// TODO Auto-generated method stub
		return null;
	}

	public void printErrorMessage(Error error) {
		switch (error) {
		case DIR_NULL:
			System.out
					.println("WatchKey was not recognized. Try restarting the application.");
			break;
		case OVERFLOW:
			System.out
					.println("Event overflow detected. Some changes might have been not noticed. "
							+ "Usually this doesn't mean there's a problem, "
							+ "but please do check that the encryption was succesfully completed.");
			break;
		default:
			break;
		}
	}

	private String getExtensionFromPath(Path path) {
		String extension = "";
		int i = path.toString().lastIndexOf('.');
		if (i > 0) {
			extension = path.toString().substring(i + 1);
		}
		return extension;
	}

	private Path encrypt(Path pathToFile) throws IOException {
		String remote = remoteDir.toString();
		System.out.println("trying axcrypt, remote is: " + remote);
		System.out.println("path to file is: " + pathToFile);

		Process axCryptProcess;

		axCryptProcess = Runtime.getRuntime().exec(
				"C:\\Program Files\\Axantum\\Axcrypt\\AxCrypt -b 2 -e -k \"testi\" -z "
						+ "\"" + pathToFile + "\"");
		InputStream rdiffStream = axCryptProcess.getInputStream();
		Reader reader = new InputStreamReader(rdiffStream);
		BufferedReader bReader = new BufferedReader(reader);
		String nextLine = null;
		while ((nextLine = bReader.readLine()) != null) {
			System.out.println(nextLine);
		}
		int exitValue = axCryptProcess.exitValue();
		if (exitValue == 0) {
			return getEncryptedFilePath(pathToFile);
		} else {
			throw (new IOException());
		}
	}
	
	private Path getEncryptedFilePath(Path path){
		return path;
		//TODO change this
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
