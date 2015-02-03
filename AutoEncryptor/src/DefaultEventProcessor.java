import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchEvent.Kind;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DefaultEventProcessor implements EventProcessor {

	public void processEvent(WatchKey key, WatchEvent<?> event) {
		Kind<?> kind = event.kind();

		if (kind == OVERFLOW) {
			AutoEncryptor.LOGGER
					.warning("Overflow error. Manual check necessary.");
		}

		if (kind == ENTRY_CREATE) {
			processCreation(key, event);
		}
	}

	private void processCreation(WatchKey key, WatchEvent<?> event) {

		Path dir = AutoEncryptor.keys.get(key);
		Path remote = AutoEncryptor.directories
				.get(AutoEncryptor.keys.get(key));
		AutoEncryptor.LOGGER.fine("Current watched directory: " + dir);
		AutoEncryptor.LOGGER.fine("Current remote directory: " + remote);

		WatchEvent<Path> ev = AutoEncryptor.cast(event);
		Path name = ev.context();
		Path pathToFile = dir.resolve(name);

		AutoEncryptor.LOGGER.config(event.kind().name() + ": " + pathToFile);

		// If directory, zip recursively
		if (new File(pathToFile.toString()).isDirectory()) {
			zipRecursively(pathToFile);
		}

		// If an ordinary file, encrypt
		else {
			encryptAndMoveFile(pathToFile, remote);
		}

	}

	void encryptAndMoveFile(Path pathToFile, Path remote) {

		String extension = getExtensionFromPath(pathToFile);
		if (!extension.equals("axx")) {
			waitUntilPathIsAccessible(pathToFile);
			if (pathIsAccessible(pathToFile)) {
				AutoEncryptor.LOGGER
						.fine("File " + pathToFile + " accessible.");
				try {
					Path encrypted = encrypt(pathToFile);
					AutoEncryptor.LOGGER.info("Encrypted " + encrypted
							+ " succesfully.");
					move(encrypted, remote);
					AutoEncryptor.LOGGER.info("Moved " + encrypted + " to "
							+ remote + " succesfully.");
				} catch (IOException e) {
					AutoEncryptor
							.logExceptionAsSevere(
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
				AutoEncryptor.LOGGER.finest("File not accessible, sleeping.");
				TimeUnit.MILLISECONDS.sleep(100);
			} catch (InterruptedException e) {
				AutoEncryptor.logExceptionAsSevere(e,
						"Interrupted while sleeping.");
			}
		}
	}

	boolean pathIsAccessible(Path path) {
		String fileName = path.toString();
		File file = new File(fileName);
		File sameFileName = new File(fileName);
		return file.renameTo(sameFileName);
	}

	void zipRecursively(Path pathToFile) {
		AutoEncryptor.LOGGER.config("File " + pathToFile
				+ " is directory, zipping.");

		waitUntilPathIsAccessible(pathToFile);
		if (pathIsAccessible(pathToFile)) {
			AutoEncryptor.LOGGER.fine("File " + pathToFile + " accessible.");
			try {
				zipDirectory(pathToFile);
				return;
			} catch (IOException e) {
				AutoEncryptor.logExceptionAsSevere(e,
						"Something went wrong while zipping!");
			}
		}

	}

	private void zipDirectory(Path path) throws IOException {
		String fileName = path.toString();
		File file = new File(fileName);

		String zipFileName = file.getCanonicalPath().concat(".zip");
		ZipOutputStream out = new ZipOutputStream(new FileOutputStream(
				zipFileName));
		AutoEncryptor.LOGGER.config("Creating: " + zipFileName);
		addDirToZip(file, out);
		out.close();
		AutoEncryptor.LOGGER.fine("Deleting directory " + file);
		if (deleteDirectory(file)) {
			AutoEncryptor.LOGGER.config("Deleted directory " + file
					+ " succesfully.");
		} else {
			AutoEncryptor.LOGGER.config("Deleting directory " + file
					+ " not succesful.");
		}
	}

	private boolean deleteDirectory(File path) {
		if (path.exists()) {
			File[] files = path.listFiles();
			for (int i = 0; i < files.length; i++) {
				if (files[i].isDirectory()) {
					deleteDirectory(files[i]);
				} else {
					AutoEncryptor.LOGGER.finest("Delete file " + files[i]);
					files[i].delete();
				}
			}
		}
		return (path.delete());
	}

	private void addDirToZip(File file, ZipOutputStream out)
			throws IOException {
		File[] files = file.listFiles();
		byte[] tmpBuf = new byte[1024];

		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory()) {
				addDirToZip(files[i], out);
				continue;
			}
			FileInputStream in = new FileInputStream(files[i].getAbsolutePath());
			AutoEncryptor.LOGGER.config("Adding to zip: "
					+ files[i].getAbsolutePath());
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
		AutoEncryptor.LOGGER.finest("Getting filename for " + file);
		Path filename = file.getFileName();
		AutoEncryptor.LOGGER.finest("Got filename for " + file);
		AutoEncryptor.LOGGER.finest("Getting new location " + newLocation);
		newLocation = Paths.get(newLocation.toString().concat("\\")
				.concat(filename.toString()));
		AutoEncryptor.LOGGER.finest("Got new location " + newLocation);
		AutoEncryptor.LOGGER.config("Moving file " + filename + " to "
				+ newLocation);
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

		String commandString = createEncryptionStringForPath(pathToFile);
		if (executeExternalCommand(commandString)) {
			return getEncryptedFilePath(pathToFile);
		} else {
			AutoEncryptor.LOGGER.severe("Encryption of file " + pathToFile
					+ " not succesful.");
			throw (new IOException());
		}
	}

	private String createEncryptionStringForPath(Path pathToFile) {
		String encryptionString = "C:\\Program Files\\Axantum\\Axcrypt\\AxCrypt -b 2 -e -k "
				+ "\""
				+ AutoEncryptor.passphrase
				+ "\""
				+ " -z "
				+ "\""
				+ pathToFile + "\"";
		return encryptionString;
	}

	private boolean executeExternalCommand(String command)
			throws IOException {
		Process process;

		process = Runtime.getRuntime().exec(command);
		InputStream stream = process.getInputStream();
		Reader reader = new InputStreamReader(stream);
		BufferedReader bReader = new BufferedReader(reader);
		String nextLine = null;
		while ((nextLine = bReader.readLine()) != null) {
			AutoEncryptor.LOGGER.config("Process output: " + nextLine);
		}
		int exitValue = process.exitValue();
		AutoEncryptor.LOGGER.config("Process exited with value: " + exitValue);
		if (exitValue == 0) {
			return true;
		} else {
			AutoEncryptor.LOGGER.severe("External command not succesful.");
			return false;
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

}
