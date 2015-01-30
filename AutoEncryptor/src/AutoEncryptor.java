import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.io.File;
import java.io.IOException;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AutoEncryptor {
	private WatchService watcher;
	private Map<WatchKey, Path> keys;
	private Map<Path, Path> directories;

	private String passphrase;

	private final static Logger LOGGER = Logger.getLogger(AutoEncryptor.class
			.getName());
	private Level logLevel;
	private static FileHandler fh;
	private static SimpleFormatter formatter;
	private Properties config;

	@SuppressWarnings("unchecked")
	static <T> WatchEvent<T> cast(WatchEvent<?> event) {
		return (WatchEvent<T>) event;
	}

	public AutoEncryptor() throws IOException {

		readConfig();
		initLogger();

		this.logLevel = Level.parse(config.getProperty("logLevel"));
		LOGGER.setLevel(logLevel);
		LOGGER.info("Log level " + logLevel);

		this.watcher = FileSystems.getDefault().newWatchService();
		this.keys = new HashMap<WatchKey, Path>();
		this.directories = new HashMap<Path, Path>();

		this.passphrase = config.getProperty("passphrase");

		register();
	}

	private void initLogger() {
		try {
			fh = new FileHandler(config.getProperty("logLocation"));
			LOGGER.addHandler(fh);
			formatter = new SimpleFormatter();
			fh.setFormatter(formatter);
		} catch (SecurityException e) {
			LOGGER.severe("Security exception during initialization.");
			LOGGER.severe(e.getStackTrace().toString());
		} catch (IOException e) {
			LOGGER.severe("IO exception during initialization.");
			LOGGER.severe(e.getStackTrace().toString());
		}
	}

	private void readConfig() throws IOException {
		this.config = new Properties();
		InputStream in;

		if (new File("ae.properties").exists()) {
			in = this.getClass().getResourceAsStream("ae.properties");
		} else {
			in = this.getClass()
					.getResourceAsStream("autoEncryptor.properties");
		}

		config.load(in);
		in.close();
	}

	private void register() throws IOException {

		int i = 1;
		String p;
		while ((p = config.getProperty("watchDir" + i)) != null) {
			Path watchDir = Paths.get(p);
			if (config.getProperty("remoteDir" + i) != null) {
				Path remoteDir = Paths.get(config.getProperty("remoteDir" + i));
				LOGGER.info("Watching " + watchDir + ", - Remote is "
						+ remoteDir);
				directories.put(watchDir, remoteDir);
				registerDirectory(watchDir);
				i++;
			} else {
				LOGGER.severe("The amount of remote directories is not equivalent "
						+ "to the amount of watched directories! "
						+ "Please check your configuration file.");
				break;
			}
			LOGGER.fine("Watcher created succesfully.");
		}
	}

	private void registerDirectory(Path dir) throws IOException {
		LOGGER.config("Registering directory " + dir);
		WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE,
				ENTRY_MODIFY);
		keys.put(key, dir);
	}

	private void processEvents() {

		LOGGER.info("Initialization succesful!");
		for (;;) {
			WatchKey key;

			try {
				key = getWatchKey();
			} catch (InterruptedException e) {
				logExceptionAsSevere(e, "Watcher interrupted.");
				return;
			}

			processEvents(key);

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				LOGGER.severe("Directory key " + key
						+ " is no longer accessible. Removing from keys.");
				keys.remove(key);

				if (keys.isEmpty()) {
					LOGGER.severe("All directories are inaccessible. Halting.");
					break;
				}
			}
		}
	}

	private void logExceptionAsSevere(Exception e, String message) {
		LOGGER.severe(message);
		LOGGER.severe(e.toString());
		LOGGER.severe(e.getMessage());
		return;
	}

	private WatchKey getWatchKey() throws InterruptedException {
		LOGGER.finest("Getting watch key.");
		WatchKey key = watcher.take();
		LOGGER.finest("Watch key: " + key);
		return key;
	}

	/**
	 * Process events for one key.
	 * 
	 * @param key
	 */
	private void processEvents(WatchKey key) {

		if (directoryIsNull(key)) {
			return;
		}

		Path dir = keys.get(key);
		Path remote = directories.get(keys.get(key));
		LOGGER.fine("Current watched directory: " + dir);
		LOGGER.fine("Current remote directory: " + remote);

		for (WatchEvent<?> event : key.pollEvents()) {
			Kind<?> kind = event.kind();

			if (kind == OVERFLOW) {
				LOGGER.warning("Overflow error. Manual check necessary.");
				continue;
			}

			if (kind == ENTRY_CREATE) {
				processCreation(event, dir, remote);
			}
		}
	}

	private boolean directoryIsNull(WatchKey key) {
		if (keys.get(key) == null) {
			LOGGER.warning("Watched directory is non-existent or not recognized.");
			return true;
		}
		return false;
	}

	private void processCreation(WatchEvent<?> event, Path dir, Path remote) {

		WatchEvent<Path> ev = cast(event);
		Path name = ev.context();
		Path pathToFile = dir.resolve(name);

		LOGGER.config(event.kind().name() + ": " + pathToFile);

		// If directory, zip recursively
		if (new File(pathToFile.toString()).isDirectory()) {
			zipRecursively(pathToFile);
		}

		// If an ordinary file, encrypt
		else {
			encryptAndMoveFile(pathToFile, remote);
		}

	}

	private void encryptAndMoveFile(Path pathToFile, Path remote) {

		String extension = getExtensionFromPath(pathToFile);
		if (!extension.equals("axx")) {
			waitUntilPathIsAccessible(pathToFile);
			if (pathIsAccessible(pathToFile)) {
				LOGGER.fine("File " + pathToFile + " accessible.");
				try {
					Path encrypted = encrypt(pathToFile);
					LOGGER.info("Encrypted " + encrypted + " succesfully.");
					move(encrypted, remote);
					LOGGER.info("Moved " + encrypted + " to " + remote
							+ " succesfully.");
				} catch (IOException e) {
					logExceptionAsSevere(
							e,
							"IO exception when moving the file. File "
									+ "might already exist or the remote may "
									+ "be inaccessible or not reachable due to for example network problems.");
					return;
				}
			}
		}
	}

	void waitUntilPathIsAccessible(Path path) {
		while (!pathIsAccessible(path)) {
			try {
				LOGGER.finest("File not accessible, sleeping.");
				TimeUnit.MILLISECONDS.sleep(100);
			} catch (InterruptedException e) {
				logExceptionAsSevere(e, "Interrupted while sleeping.");
			}
		}
	}

	boolean pathIsAccessible(Path path) {
		String fileName = path.toString();
		File file = new File(fileName);
		File sameFileName = new File(fileName);
		return file.renameTo(sameFileName);
	}

	private void zipRecursively(Path pathToFile) {
		LOGGER.config("File " + pathToFile + " is directory, zipping.");

		waitUntilPathIsAccessible(pathToFile);
		if (pathIsAccessible(pathToFile)) {
			LOGGER.fine("File " + pathToFile + " accessible.");
			try {
				zipDirectory(pathToFile);
				return;
			} catch (IOException e) {
				logExceptionAsSevere(e, "Something went wrong while zipping!");
			}
		}

	}

	private void zipDirectory(Path path) throws IOException {
		String fileName = path.toString();
		File file = new File(fileName);

		String zipFileName = file.getCanonicalPath().concat(".zip");
		ZipOutputStream out = new ZipOutputStream(new FileOutputStream(
				zipFileName));
		LOGGER.config("Creating: " + zipFileName);
		addDir(file, out);
		out.close();
		LOGGER.fine("Deleting directory " + file);
		if (deleteDirectory(file)) {
			LOGGER.config("Deleted directory " + file + " succesfully.");
		} else {
			LOGGER.config("Deleting directory " + file + " not succesful.");
		}
	}

	private boolean deleteDirectory(File path) {
		if (path.exists()) {
			File[] files = path.listFiles();
			for (int i = 0; i < files.length; i++) {
				if (files[i].isDirectory()) {
					deleteDirectory(files[i]);
				} else {
					LOGGER.finest("Delete file " + files[i]);
					files[i].delete();
				}
			}
		}
		return (path.delete());
	}

	private void addDir(File file, ZipOutputStream out) throws IOException {
		File[] files = file.listFiles();
		byte[] tmpBuf = new byte[1024];

		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory()) {
				addDir(files[i], out);
				continue;
			}
			FileInputStream in = new FileInputStream(files[i].getAbsolutePath());
			LOGGER.config("Adding to zip: " + files[i].getAbsolutePath());
			String relativePath = file.toURI().relativize(files[i].toURI())
					.toString();
			out.putNextEntry(new ZipEntry(relativePath));
			int len;
			while ((len = in.read(tmpBuf)) > 0) {
				out.write(tmpBuf, 0, len);
			}
			out.closeEntry();
			in.close();
		}
	}

	private void move(Path file, Path newLocation) throws IOException {
		LOGGER.finest("Getting filename for " + file);
		Path filename = file.getFileName();
		LOGGER.finest("Got filename for " + file);
		LOGGER.finest("Getting new location " + newLocation);
		newLocation = Paths.get(newLocation.toString().concat("\\")
				.concat(filename.toString()));
		LOGGER.finest("Got new location " + newLocation);
		LOGGER.config("Moving file " + filename + " to " + newLocation);
		Files.move(file, newLocation);
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
		LOGGER.config("Encrypting file " + pathToFile);
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
			LOGGER.config("Process output: " + nextLine);
		}
		int exitValue = axCryptProcess.exitValue();
		LOGGER.config("Process exited with value: " + exitValue);
		if (exitValue == 0) {
			return getEncryptedFilePath(pathToFile);
		} else {
			LOGGER.severe("Encryption of file " + pathToFile
					+ " not succesful.");
			throw (new IOException());
		}

	}

	private Path getEncryptedFilePath(Path path) {
		String extension = getExtensionFromPath(path);
		String pathString = path.toString();

		int lastIndex = pathString.lastIndexOf(extension);
		int dotIndex = lastIndex;
		if (!extension.equals(""))
			dotIndex = lastIndex - 1;
		String pathNoExt = pathString.substring(0, dotIndex);

		String newPath;
		if (!extension.equals(""))
			newPath = pathNoExt + "-" + extension + ".axx";
		else
			newPath = pathNoExt + ".axx";

		path = Paths.get(newPath);
		return path;
	}

	public static void main(String[] args) throws IOException {
		new AutoEncryptor().processEvents();
	}

}
