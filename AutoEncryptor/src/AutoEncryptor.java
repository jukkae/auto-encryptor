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

public class AutoEncryptor {
	private WatchService watcher;
	private Map<WatchKey, Path> keys;
	private Map<Path, Path> directories;

	private String passphrase;

	private final static Logger LOGGER = Logger.getLogger(AutoEncryptor.class
			.getName());
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

		this.watcher = FileSystems.getDefault().newWatchService();
		this.keys = new HashMap<WatchKey, Path>();
		this.directories = new HashMap<Path, Path>();

		this.passphrase = config.getProperty("passphrase");

		register();

		LOGGER.setLevel(Level.ALL);
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
				LOGGER.info("Putting pair " + watchDir + " - " + remoteDir
						+ " into correspondence map.");
				directories.put(watchDir, remoteDir);
				registerDirectory(watchDir);
				i++;
			} else {
				LOGGER.severe("The amount of remote directories is not equivalent "
						+ "to the amount of watched directories! "
						+ "Please check your configuration file.");
				break;
			}
		}
	}

	private void registerDirectory(Path dir) throws IOException {
		LOGGER.info("Registering directory " + dir);
		WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE,
				ENTRY_MODIFY);
		keys.put(key, dir);
	}

	private void processEvents() {

		LOGGER.info("Watcher created succesfully.");
		for (;;) {
			WatchKey key;

			try {
				LOGGER.config("Getting watch key.");
				key = watcher.take();
			} catch (InterruptedException x) {
				LOGGER.severe("Watcher interrupted.");
				LOGGER.severe(x.getStackTrace().toString());
				return;
			}

			Path dir = keys.get(key);
			if (dir == null) {
				LOGGER.warning("Non-existent directory or directory not recognized.");
				continue;
			}

			Path remote = directories.get(keys.get(key));
			LOGGER.info("Current watched directory: " + dir);
			LOGGER.info("Current remote directory: " + remote);

			for (WatchEvent<?> event : key.pollEvents()) {
				Kind<?> kind = event.kind();

				if (kind == OVERFLOW) {
					LOGGER.warning("Overflow error. Manual check might be necessary.");
					continue;
				}

				WatchEvent<Path> ev = cast(event);
				Path name = ev.context();
				Path pathToFile = dir.resolve(name);

				LOGGER.info(event.kind().name() + ": " + pathToFile);
				if (kind == ENTRY_CREATE) {

					String fileName = pathToFile.toString();
					File file = new File(fileName);
					File sameFileName = new File(fileName);

					String extension = getExtensionFromPath(pathToFile);
					if (!extension.equals("axx")) {
						while (!file.renameTo(sameFileName)) {
							try {
								LOGGER.config("File not accessible, sleeping.");
								TimeUnit.MILLISECONDS.sleep(100);
							} catch (InterruptedException e) {
								LOGGER.warning("Interrupted while sleeping.");
								LOGGER.warning(e.getStackTrace().toString());
							}
						}
						if (file.renameTo(sameFileName)) {
							try {
								Path encrypted = encrypt(pathToFile);
								LOGGER.info("Encryption succesful.");
								move(encrypted, remote);
							} catch (IOException e) {
								LOGGER.severe("IO exception when moving the file. File "
										+ "might already exist or the remote may "
										+ "be inaccessible.");
								LOGGER.severe(e.getStackTrace().toString());
								break;
							}
						}
					}
				}
			}

			// reset key and remove from set if directory no longer accessible
			boolean valid = key.reset();
			if (!valid) {
				LOGGER.info("Directory key " + key
						+ " is no longer accessible. Removing from keys.");
				keys.remove(key);

				if (keys.isEmpty()) {
					LOGGER.info("All directories are inaccessible. Halting.");
					break;
				}
			}
		}
	}

	private void move(Path file, Path newLocation) throws IOException {
		Path filename = file.getFileName();
		newLocation = Paths.get(newLocation.toString().concat("\\")
				.concat(filename.toString()));
		LOGGER.info("Moving file to " + newLocation);
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
		LOGGER.info("Encrypting.");
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
			LOGGER.info("Process output: " + nextLine);
		}
		int exitValue = axCryptProcess.exitValue();
		LOGGER.info("Process exited with value: " + exitValue);
		if (exitValue == 0) {
			LOGGER.info("Encryption succesful.");
			return getEncryptedFilePath(pathToFile);
		} else {
			LOGGER.severe("Encryption not succesful.");
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
