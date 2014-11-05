package de.k3b.zip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CompressJob {
    private static final Logger logger = LoggerFactory.getLogger(CompressJob.class);

    // global settings
    /** remove obsoled bak file when done */
    private boolean optDeleteBakFileWhenFinished = true;

    /**
     * if filename already existed in old zip rename it. Else Do not copy old
     * zipItem into new.
     */
    private boolean optRenameExistingOldEntry = true;

    /** items to be processed in the job. */
    private List<CompressItem> items = new ArrayList<CompressItem>();

    /** where old entries come from and new entries go to */
    private File destZip;

    // used to copy content
    private byte[] buffer = new byte[4096];

    public CompressJob(File destZip) {
        this.destZip = destZip;
    }

    /** these files are added to the zip */
    public void add(String destZipPath, String... srcFiles) {
        if (srcFiles != null) {
            for (String srcFile : srcFiles) {
                addItem(destZipPath, new File(srcFile));
            }
        }

    }

    /** this file will be added to the zip. package to allow unittesting */
    CompressItem addItem(String destZipPath, File srcFile) {
        CompressItem item = new CompressItem().setFile(srcFile).setZipFileName(
                destZipPath + srcFile.getName());
        items.add(item);
        return item;
    }

    /** depending on global options: duprlicate zip entries are either ignored or renamed */
    public List<CompressItem> handleDuplicates() {
        if ((this.destZip != null) && (this.destZip.exists())) {

            ZipFile zipFile = null;
            try {
                zipFile = new ZipFile(this.destZip);
                return handleDuplicates(zipFile);
            } catch (ZipException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } finally {
                if (zipFile != null)
                    try {
                        zipFile.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
            }
        }
        return null;
    }

    /** a unittest friendly version of handleDuplicates:<br/>
     * depending on global options: duprlicate zip entries are either ignored or renamed */
    List<CompressItem> handleDuplicates(ZipFile zipFile) {
        List<CompressItem> result = new ArrayList<CompressItem>();
        for(CompressItem item : this.items) {
            String zipFileName = (item != null) ? item.getZipFileName() : null;
            ZipEntry zipEntry = (zipFileName != null) ? zipFile.getEntry(zipFileName) : null;

            if (zipEntry != null) {
                item.setZipFileName(getFixedZipFileName(zipFile, zipEntry,
                        item.getFile().lastModified()));
                result.add(item);
            }
        }
        for(int i = this.items.size() - 1; i>= 0; i--) {
            if (this.items.get(i).getZipFileName() == null) {
                this.items.remove(i);
            }
        }

        if (result.size() > 0) {
            return result;
        }
        return null;
    }

    /** package to allow unittesting: <br/> gets a fixed name for the zip entry or null if file
     * should not be added to zip. */
    String getFixedZipFileName(ZipFile zipFile, ZipEntry zipEntry,
                                       long lastModified) {
        String zipFileName = zipEntry.getName();
        if (!optRenameExistingOldEntry) {
            logger.debug("do not include: optRenameExistingOldEntry disabled {}", zipFileName);
            return null;
        }

        if (sameDate(zipEntry, lastModified)) {
            logger.debug("do not include: duplicate with same datetime found {}", zipFileName);
            return null;
        }

        String extension = ")";
        int extensionPosition = zipFileName.lastIndexOf(".");
        if (extensionPosition >= 0) {
            extension = ")" + zipFileName.substring(extensionPosition);
            zipFileName = zipFileName.substring(0, extensionPosition) + "(";
        }
        int id = 1;
        while (true) {
            String newZifFileName = zipFileName + id + extension;
            ZipEntry newZipEntry = zipFile.getEntry(newZifFileName);
            if (newZipEntry == null) {
                logger.debug("renamed zipentry from '{}' to '{}'", zipFileName, newZifFileName);
                return newZifFileName;
            }

            if (sameDate(newZipEntry, lastModified)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("do not include: duplicate with same datetime found '{}' for '{}'",
                        newZifFileName, zipFileName);
                }
                return null;
            }

            id++;
        }
    }

    private boolean sameDate(ZipEntry zipEntry, long fileLastModified) {
        // may varay in millisec
        return (fileLastModified / 1000) == (zipEntry.getTime() / 1000);
    }

    // to make shure that orginal is not broken if there is an error:
    // 1) Workflow add to somefile.zip.tmp, (a) old content, (b) new content
    // 2) rename exising to somefile.zip.bak
    // 3) rename somefile.zip.tmp to somefile.zip
    // 4) delete exising to somefile.zip.bak
    /** @return number of items in the result zip or -1 if no change */
    public int compress() {
        handleDuplicates();

        if (items.size() == 0) {
            logger.debug("aboard: no (more) files to add to zip");
            return -1;
        }

        // global to allow garbage collection if there is an exception
        ZipOutputStream out = null;
        ZipInputStream zipInputStream = null;
        InputStream inputStream = null;
        String context = "";
        int itemCount = 0;

        try {
            // i.e. /path/to/somefile.zip.tmp
            File newZip = new File(this.destZip.getAbsolutePath() + ".tmp");
            File oldZip = null;

            newZip.delete();
            context = getMessage("(0) create new result file {0}", newZip);
            out = new ZipOutputStream(new FileOutputStream(newZip));

            if (this.destZip.exists()) {
                context = getMessage("(1a) copy existing items from {0} to {1}",
                        this.destZip, newZip);
                zipInputStream = new ZipInputStream(new FileInputStream(
                        this.destZip));

                for (ZipEntry zipOldEntry = zipInputStream.getNextEntry(); zipOldEntry != null; zipOldEntry = zipInputStream
                        .getNextEntry()) {
                    if (null != zipOldEntry) {
                        context = getMessage(
                                "- (1a) copy existing item from {0} to {1} : {2}",
                                this.destZip, newZip, zipOldEntry);
                        add(out, zipOldEntry, zipInputStream);
                        itemCount++;
                    }
                }
                zipInputStream.close();
                zipInputStream = null;
                // i.e. /path/to/somefile.zip.bak
                oldZip = new File(this.destZip.getAbsolutePath() + ".bak");
            }

            // (1b) copy new items
            for (CompressItem item : this.items) {
                String newFullDestZipItemName = item.getZipFileName();
                File file = item.getFile();
                context = getMessage("(1b) copy new item {0} as {1} to {2}",
                        file, newFullDestZipItemName, newZip);
                inputStream = new FileInputStream(file);
                ZipEntry zipEntry = createZipEntry(newFullDestZipItemName,
                        file.lastModified(), null);
                add(out, zipEntry, inputStream);
                inputStream.close();
                inputStream = null;
                itemCount++;
            }

            out.close();
            out = null;

            // no exception yet: Assume it is save to change the old zip
            // (2) rename exising-old somefile.zip to somefile.zip.bak
            if (oldZip != null) {
                oldZip.delete(); // should ignore error

                context = getMessage(
                        "(2) rename old zip file from {0}  to {1}",
                        this.destZip, oldZip);
                // i.e. /path/to/somefile.zip => /path/to/somefile.zip.bak
                if (!this.destZip.renameTo(oldZip)) {
                    thowrError("failed in " + context);
                }
            }

            // 3) rename new created somefile.zip.tmp to somefile.zip
            context = getMessage("(3) rename new created zip file {0} to {1}",
                    newZip, this.destZip);
            if (!newZip.renameTo(this.destZip)) {
                // something went wrong. try to restore old zip
                // i.e. somefile.zip.bak => somefile.zip
                if (oldZip != null) {
                    oldZip.renameTo(this.destZip);
                }

                thowrError("failed in " + context);
            }

            // 4) delete exising renamed old somefile.zip.bak
            if ((optDeleteBakFileWhenFinished) && (oldZip != null)) {
                context = getMessage(
                        "(4) delete exising renamed old zip file {0}", oldZip);
                oldZip.delete();
            }
            context = getMessage("(5a) successfull updated zip file {0}",
                    this.destZip);

        } catch (Exception e) {
            System.err.println("Exception in " + context);
            e.printStackTrace();
        } finally {
            // 3) rename new created somefile.zip.tmp to somefile.zip
            context = getMessage("(5b) free resources");

            try {
                if (inputStream != null)
                    inputStream.close();
                if (zipInputStream != null)
                    zipInputStream.close();
                if (out != null)
                    out.close();
            } catch (IOException e) {
                System.err.println("Exception in " + context);
                e.printStackTrace();
            }
        }
        return itemCount;
    }

    private ZipEntry createZipEntry(String renamedFile, long time,
                                    String comment) {
        ZipEntry result = new ZipEntry(renamedFile);
        if (time != 0)
            result.setTime(time);
        if (comment != null)
            result.setComment(comment);

        return result;
    }

    /** formats context message and does low level logging */
    private String getMessage(String format, Object... params) {
        String result = MessageFormat.format(format, params);
        logger.debug(result);
        // System.out.println(result);
        return result;
    }

    private void thowrError(String message) throws Exception {
        throw new Exception(message);
    }

    /** add one item to zip */
    private void add(ZipOutputStream outZipStream, ZipEntry zipEntry,
                     InputStream inputStream) throws IOException {
        outZipStream.putNextEntry(zipEntry);
        copyStream(outZipStream, inputStream, buffer);
        outZipStream.closeEntry();
    }

    static void copyStream(OutputStream outputStream, InputStream inputStream, byte[] buffer) throws IOException {
        for (int read = inputStream.read(buffer); read > -1; read = inputStream
                .read(buffer)) {
            outputStream.write(buffer, 0, read);
        }
    }

}
