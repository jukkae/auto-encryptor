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
	private String passphrase;

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

	public AutoEncryptor(Path dir, Path remoteDir, String passphrase)
			throws IOException {
		this.watcher = FileSystems.getDefault().newWatchService();
		this.keys = new HashMap<WatchKey, Path>();
		this.remoteDir = remoteDir;
		this.passphrase = passphrase;

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
		System.out
				.println("Watcher created succesfully. Watching for changes in the filesystem.");
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
							System.out
									.println("Encryption succesfull! The path to encrypted file is "
											+ encrypted);
							// Path newLocation = parseNewLocation(encrypted);
							// move(encrypted, newLocation);
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

	private Path parseNewLocation(Path path) {
		System.out.println(path);
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
				"C:\\Program Files\\Axantum\\Axcrypt\\AxCrypt -b 2 -e -k "
						+ "\"" + passphrase + "\"" + " -z " + "\"" + pathToFile
						+ "\"");
		InputStream stream = axCryptProcess.getInputStream();
		Reader reader = new InputStreamReader(stream);
		BufferedReader bReader = new BufferedReader(reader);
		String nextLine = null;
		while ((nextLine = bReader.readLine()) != null) {
			System.out.println(nextLine);
		}
		int exitValue = axCryptProcess.exitValue();
		System.out.println(exitValue);
		if (exitValue == 0) {
			return getEncryptedFilePath(pathToFile);
		} else {
			throw (new IOException());
		}

	}

	private Path getEncryptedFilePath(Path path) {
		String extension = getExtensionFromPath(path);
		System.out.println("Old extension: " + extension);
		String pathString = path.toString();
		System.out.println("Path string is: " + pathString);

		int lastIndex = pathString.lastIndexOf(extension);
		int dotIndex = lastIndex;
		if (!extension.equals(""))
			dotIndex = lastIndex - 1;
		String pathNoExt = pathString.substring(0, dotIndex);
		System.out.println("Stripped path string is: " + pathNoExt);

		// TODO only works with .axx currently!
		String newPath;
		if (!extension.equals(""))
			newPath = pathNoExt + "-" + extension + ".axx";
		else
			newPath = pathNoExt + ".axx";
		System.out.println("New file path is: " + newPath);

		path = Paths.get(newPath);
		System.out.println("Path generated succesfully: " + path);
		return path;
		// TODO change this
	}

	public static void usage() {
		System.out.println("Usage information:");
		System.out.println("Command line arguments:");
		System.out.println("watchDir remoteDir passphrase");
	}

	public static void main(String[] args) throws IOException {
		// parse arguments
		if (args.length != 3)
			usage();

		Path dir = Paths.get(args[0]);
		Path remoteDir = Paths.get(args[1]);
		String passphrase = args[2];
		new AutoEncryptor(dir, remoteDir, passphrase).processEvents();
	}

}
