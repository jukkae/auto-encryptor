import java.nio.file.*;
import java.io.File;
import java.io.IOException;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.*;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class AutoEncryptor {
	private WatchService watcher;
	private ArrayList<EventProcessor> eventProcessors;
	static Map<WatchKey, Path> keys;
	static Map<Path, Path> directories;

	static String passphrase;

	public final static Logger LOGGER = Logger.getLogger(AutoEncryptor.class
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
		
		this.eventProcessors = new ArrayList<EventProcessor>();
 
		this.logLevel = Level.parse(config.getProperty("logLevel"));
		LOGGER.setLevel(logLevel);
		LOGGER.info("Log level " + logLevel);

		this.watcher = FileSystems.getDefault().newWatchService();
		keys = new HashMap<WatchKey, Path>();
		directories = new HashMap<Path, Path>();

		passphrase = config.getProperty("passphrase");

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
			// TODO iterate through arraylist
			
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

	static void logExceptionAsSevere(Exception e, String message) {
		LOGGER.severe(message);
		LOGGER.severe(e.toString());
		LOGGER.severe(e.getMessage());
		return;
	}

	private WatchKey getWatchKey() throws InterruptedException {
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
		if (!directoryIsNull(key)) {
			for (WatchEvent<?> event : key.pollEvents()) {
				DefaultEventProcessor.processEvent(key, event);
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

	public static void main(String[] args) throws IOException {
		new AutoEncryptor().processEvents();
	}

}
