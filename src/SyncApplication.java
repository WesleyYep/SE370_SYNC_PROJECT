import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.json.*;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonGeneratorFactory;
import java.security.MessageDigest;
import static java.nio.file.StandardCopyOption.*;

public class SyncApplication {
    private static final String HASH_ALGORITHM = "SHA-256";
    private JsonObject json1;
    private JsonObject json2;
    private File[] files;
    private File[] files2;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
    private String dir1;
    private String dir2;


    public static void main(String[] args) {
        String initialDir1;
        String initialDir2;

        if (args.length < 2) {
        	System.out.println("Please use the correct format: ./sync dir1 dir2");
        	return;
        } else {
            initialDir1 = args[0];
            initialDir2 = args[1];
        }

        SyncApplication app = new SyncApplication();
        
        try {
            app.sync(initialDir1, initialDir2);
        } catch (IOException | NoSuchAlgorithmException | ParseException e) {
  //        e.printStackTrace();
        }

    }

    private void sync(String dir1, String dir2) throws IOException, NoSuchAlgorithmException, ParseException {
        this.dir1 = dir1;
        this.dir2 = dir2;
        
        if (!Files.exists(Paths.get(dir1)) && !Files.exists(Paths.get(dir2))) {
            System.out.println("Both directories do not exist. Program will now end");
            return;
        } 
        
        File directory1 = new File("./" + dir1);
        File directory2 = new File("./" + dir2);

        if (directory1.isFile() || directory2.isFile()) {
            System.out.println("One or more directories are not actual directories. Program will now end");
        	return;
        }

        if (!Files.exists(Paths.get(dir1))) {
            System.gc();
            Files.createDirectory(Paths.get(dir1));
//            System.out.println("creating directory1 as it doesn't exist");
        }
        if (!Files.exists(Paths.get(dir2))) {
            System.gc();
            Files.createDirectory(Paths.get(dir2));
//            System.out.println("creating directory2 as it doesn't exist");
        }

        //get all files from dir1
        files = directory1.listFiles();
        files2 = directory2.listFiles();

        //get json arrays from .sync files
        String pathToFirstSync = "./" + dir1 + "/.sync";
        String pathToSecondSync = "./" + dir2 + "/.sync";
        File syncFile = new File(pathToFirstSync);
        File syncFile2 = new File(pathToSecondSync);

        syncFile.createNewFile();
        syncFile2.createNewFile();

        json1 = readJson(syncFile);
        json2 = readJson(syncFile2);

        writeJson(json1, dir1, 1);
        writeJson(json2, dir2, 2);

        //compare sync files
        json1 = readJson(syncFile);
        json2 = readJson(syncFile2);

        merge(dir1, dir2);

        


    }

    /**
     * Creates a SHA-256 hash checksum of a file.
     * Code borrowed and modified from http://www.mkyong.com/java/java-sha-hashing-example/
     *
     * @param filePath path to the file being hashed
     * @return the hex string representing the SHA-256 hash of the file contents
     */
    private String generateHash(String filePath) throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
        FileInputStream fis = new FileInputStream(filePath);

        byte[] dataBytes = new byte[1024];

        int nread = 0;
        while ((nread = fis.read(dataBytes)) != -1) {
            md.update(dataBytes, 0, nread);
        }
        ;
        byte[] mdbytes = md.digest();

        //convert the byte to hex
        StringBuilder sb = new StringBuilder();
        for (byte mdbyte : mdbytes) {
            sb.append(Integer.toString((mdbyte & 0xff) + 0x100, 16).substring(1));
        }

//        System.out.println("Hex format : " + sb.toString());
        fis.close();
        return sb.toString();
    }

    /**
     * This method merges the two directories based on sync files
     * @param dir1 name of first directory
     * @param dir2 name of second directory
     */
    private void merge(String dir1, String dir2) throws IOException, ParseException, NoSuchFileException {
        OutputStream os1 = new FileOutputStream(dir1 + "/.sync");
        OutputStream os2 = new FileOutputStream(dir2 + "/.sync");

        Map<String, Object> properties = new HashMap<>(1);
        properties.put(JsonGenerator.PRETTY_PRINTING, true); //pretty print for easier debugging
        JsonGeneratorFactory jf = Json.createGeneratorFactory(properties);

        JsonGenerator jsonWriter1 = jf.createGenerator(os1);
        jsonWriter1.writeStartObject();
        JsonGenerator jsonWriter2 = jf.createGenerator(os2);
        jsonWriter2.writeStartObject();

        for (Map.Entry<String, JsonValue> e : json1.entrySet()) {
            String key = e.getKey();
            JsonValue value = e.getValue();
            File file1 = new File(dir1 + "/" + key);
            File file2 = new File(dir2 + "/" + key);

            if (!json2.keySet().contains(e.getKey())) {
//                System.out.println("dir2 file doesn't exist in sync file - " + e.getKey()); //need to add
                Files.copy(file1.toPath(), Paths.get(dir2 + "/" + key), REPLACE_EXISTING);
            } else {
                String digest1 = json1.getJsonArray(e.getKey()).getJsonArray(0).get(1).toString();
                String digest2 = json2.getJsonArray(e.getKey()).getJsonArray(0).get(1).toString();
                Date timestamp1 = sdf.parse(json1.getJsonArray(e.getKey()).getJsonArray(0).get(0).toString());
                Date timestamp2 = sdf.parse(json2.getJsonArray(e.getKey()).getJsonArray(0).get(0).toString());

                if (digest1.equals("deleted") && !digest2.equals("deleted") && checkNotRecreated(json2.getJsonArray(e.getKey()), timestamp1)) {
                    System.gc();
                    String success = file2.delete() ? "deleted succeeded " : "delete failed ";
//                    System.out.println(success + e.getKey()); //no need to update
                } else if (digest2.equals("deleted") && !digest1.equals("deleted") && checkNotRecreated(json1.getJsonArray(e.getKey()), timestamp2)) {
//                    System.out.println("dir2 file was deleted - " + e.getKey()); //deletion takes priority, delete from here
                    value = updateJson(0, timestamp2, "deleted", (JsonArray)value); //now should change modified time to equal dir2
                    System.gc();
                    String success = file1.delete() ? "deleted succeeded " : "delete failed ";
//                    System.out.println(success + e.getKey()); //no need to update
                } else if (!digest2.equals(digest1)) {
                  //look through to see if current digest2 is equal to an earlier version of digest 1
                    //also should check vice versa, since if both are unique, need to check timestamps
                   int val = compareAndUpdate(json1.getJsonArray(e.getKey()), json2.getJsonArray(e.getKey()), file1, file2);

                    if (val == 1) { //1 means dir1 should update, 2 means dir2 should update
                        value = updateJson(0, timestamp2, digest2, (JsonArray)value); //now should change modified time to equal dir2
                    } else if (val == 2) {
                        //if val = 2 then dir 2 should update so we don't need to change anything here
                    } else {
                        //val = -1 means both are unique, use timestamp to determine which to change
                        if (timestamp1.getTime() < timestamp2.getTime()) { //second is newer
                            value = updateJson(0, timestamp2, digest2, (JsonArray)value); //now should change modified time to equal dir2
                            copyFile(file2, file1);
                        } else {// otherwise 1 is newer, so copy file 1 into 2
                            copyFile(file1, file2);
                        }
                    }
                } else if (!digest1.equals("deleted") && timestamp2.getTime() < timestamp1.getTime()) {
//                    System.out.println("dir2 file is younger than dir1 file - " + e.getKey());//no need to change value
                    String success = file2.setLastModified(timestamp1.getTime()) ? "modify succeeded " : "modify failed ";
//                    System.out.println(success);
                } else if (!digest1.equals("deleted") && timestamp2.getTime() > timestamp1.getTime()) {
//                    System.out.println("dir1 file is younger than dir2 file - " + e.getKey());
                    value = updateJson(0, timestamp2, "", (JsonArray)value); //now should change modified time to equal dir2
                    String success = file1.setLastModified(timestamp2.getTime()) ? "modify succeeded " : "modify failed ";
//                    System.out.println(success);
                } else {
                    //just add as normal
                }
            }
            jsonWriter1.write(key, value);
            jsonWriter2.write(key, value);
        }

        //now just need to handle the values in sync2 that are not in sync1
        for (Map.Entry<String, JsonValue> e : json2.entrySet()) {
            if (!json1.keySet().contains(e.getKey())) {
//                System.out.println("dir1 file doesn't exist in sync file - " + e.getKey()); //need to add
                JsonArray val = (JsonArray) e.getValue();
                if (!val.getJsonArray(0).get(1).toString().equals("deleted")) {
                    System.gc();
                    Files.copy(Paths.get(dir2 + "/" + e.getKey()), Paths.get(dir1 + "/" + e.getKey()), REPLACE_EXISTING);
                }
                jsonWriter1.write(e.getKey(), e.getValue());
                jsonWriter2.write(e.getKey(), e.getValue());
            }
        }

        jsonWriter1.writeEnd();
        jsonWriter2.writeEnd();
        jsonWriter1.close();
        jsonWriter2.close();
        os1.close();
        os2.close();

    }

    private boolean checkNotRecreated(JsonArray jsonArray, Date timestamp1) throws ParseException {

        for (int i = 0; i < jsonArray.size(); i++) {
            JsonArray entry = jsonArray.getJsonArray(i);

            if (entry.get(1).toString().equals("deleted") && sdf.parse(entry.get(0).toString()).equals(timestamp1)) {
                return false; //has been recreated
            }
        }
        return true; //has not been recreated
    }

    private JsonArray updateJson(int pos, Date timestamp, String fileDigest, JsonArray array) {
        JsonArrayBuilder builder = Json.createArrayBuilder();

        for (int i = 0; i < array.size(); i++) {
            if (i != pos) {
                builder.add(array.get(0));
            } else {
                JsonArrayBuilder newEntryBuilder = Json.createArrayBuilder();

                if (timestamp != null) {
                    newEntryBuilder.add(sdf.format(timestamp.getTime()));
                } else {
                    newEntryBuilder.add(array.getJsonArray(pos).get(0));
                }
                if (!fileDigest.equals("")){
                    newEntryBuilder.add(fileDigest);
                } else {
                    newEntryBuilder.add(array.getJsonArray(pos).get(1));
                }

                builder.add(newEntryBuilder.build());
            }
        }

        return builder.build();
    }

    /** look through to see if current digest2 is equal to an earlier version of digest 1
     *   also should check vice versa, since if both are unique, need to check timestamps
     *   Will copy the required file accordingly (latest version is copied over)
     *
     * @param jsonArray1 json array from the file in dir1
     * @param jsonArray2 json array from the file in dir2
     *@return  @return 1 if directory 1 should update, 2 if directory 2 should update
     * @throws IOException 
     * @throws NoSuchFileException 
     */
    private int compareAndUpdate(JsonArray jsonArray1, JsonArray jsonArray2, File file1, File file2) throws NoSuchFileException, IOException {
        JsonArray json1First = jsonArray1.getJsonArray(0);
        JsonArray json2First = jsonArray2.getJsonArray(0);

        for (int i = 0; i < jsonArray1.size(); i++) {
            JsonArray current = jsonArray1.getJsonArray(i);
            if (json2First.get(1).equals(current.get(1))) {
//              System.out.println("file contents need to be changed in dir2 - " + file1.getName());
                copyFile(file1, file2);
                return 2;
            }
        }
        for (int i = 0; i < jsonArray2.size(); i++) {
            JsonArray current = jsonArray2.getJsonArray(i);
            if (json1First.get(1).equals(current.get(1))) {
 //             System.out.println("file contents need to be changed in dir1" + file2.getName());
                copyFile(file2, file1);
                return 1;
            }
        }
        return -1;
    }

    private void copyFile(File source, File dest) throws IOException, NoSuchFileException {
        Files.copy(source.toPath(), dest.toPath(), REPLACE_EXISTING);
    }

    /**Writes to sync file from contents of directory
     *
     * @param obj - either json1 or json 2, depending on the directory being looked at
     * @throws FileNotFoundException
     */
    private void writeJson(JsonObject obj, String dir, int dirNum) throws NoSuchAlgorithmException, IOException, ParseException {
        OutputStream os = new FileOutputStream(dir + "/.sync");
        Map<String, Object> properties = new HashMap<>(1);
        properties.put(JsonGenerator.PRETTY_PRINTING, true); //pretty print for easier debugging
        JsonGeneratorFactory jf = Json.createGeneratorFactory(properties);
        JsonGenerator jsonWriter = jf.createGenerator(os);
        jsonWriter.writeStartObject();

        //firstly add all existing
        if (obj != null) {
            for (Map.Entry<String, JsonValue> e : obj.entrySet()) {
                String key = e.getKey();
                JsonArray arr = (JsonArray) e.getValue();
                //compare digest of first value in array with current digest for that file
                //if different, add to that particular array
                JsonArray first = arr.getJsonArray(0);
                String fileDigestFromJson = first.getString(1);
                String newFileDigest;

                try {
                    newFileDigest = generateHash("./" + dir + "/" + key);
                }catch (IOException e1) {
                    newFileDigest = "deleted";
//                    System.out.println(key + " has been deleted");
                }
                if (fileDigestFromJson.equals(newFileDigest)) {
//                    System.out.println("equal!");
                    //add the same entry to the object builder
                    jsonWriter.write(e.getKey(), e.getValue());
                    //set file modification time to that of syncfile
                    File f = searchFiles(key, obj, dirNum);
                    if (!newFileDigest.equals("deleted") && !f.setLastModified(sdf.parse(first.get(0).toString()).getTime())) {
                        System.out.println("File modification time not able to be changed");
                    }
                } else {
//                    System.out.println("not equal");
                    //add new value to array, add to object bulder
                    File f = searchFiles(key, obj, dirNum);
                    JsonArrayBuilder newEntryBuilder = Json.createArrayBuilder();
                    if (f != null) {
                        newEntryBuilder.add(sdf.format(f.lastModified()));
                    } else {
                        newEntryBuilder.add(sdf.format(System.currentTimeMillis()));
                    }
                    newEntryBuilder.add(newFileDigest);
                    JsonArray newEntryArray = newEntryBuilder.build();
                    JsonArrayBuilder fileArrayBuilder = Json.createArrayBuilder();
                    fileArrayBuilder.add(newEntryArray);
                    for (int i = 0; i < arr.size(); i++) {
                        fileArrayBuilder.add(arr.get(i));
                    }
                    jsonWriter.write(e.getKey(), fileArrayBuilder.build());
//                    System.out.println(fileDigestFromJson + " - " + newFileDigest);
                }
            }
        }

        //now check if we missed any files
        File[] filesToSearch;

        if (dirNum == 1) {
            filesToSearch = files;
        } else {
            filesToSearch = files2;
        }
        for (File f : filesToSearch) {
            if (f.isDirectory()) {
                SyncApplication newSync = new SyncApplication();
                newSync.sync(dir1 + "/" + f.getName(), dir2 + "/" + f.getName());
                continue;
            }
            if (!f.getName().equals(".sync") && (obj == null || !obj.containsKey(f.getName()))) {
                JsonArrayBuilder fileArrayBuilder = Json.createArrayBuilder();
                JsonArrayBuilder entryBuilder = Json.createArrayBuilder();

                entryBuilder.add(sdf.format(f.lastModified()));
                entryBuilder.add(generateHash(f.getAbsolutePath()));
                fileArrayBuilder.add(entryBuilder.build());
                jsonWriter.write(f.getName(), fileArrayBuilder.build());
//                System.out.println("file added! " + f.getName());
            }
        }

        jsonWriter.writeEnd();
        jsonWriter.close();
        os.close();

    }

    private JsonObject readJson(File syncFile) {
        try {
            JsonReader rdr = Json.createReader(new FileInputStream(syncFile));
            JsonObject obj = rdr.readObject();
            rdr.close();
            return obj;
        } catch (FileNotFoundException e) {
//            System.out.println("no sync file found");
        } catch (JsonException e) {
//            System.out.println("sync file has invalid json");
        }
        return null;
    }

    private File searchFiles(String fileName, JsonObject obj, int dirNum) {
        File[] filesToSearch;

        if (dirNum == 1) {
            filesToSearch = files;
        } else {
            filesToSearch = files2;
        }

        for (File f : filesToSearch) {
            if (f.getName().equals(fileName)) {
                return f;
            }
        }
        return null;
    }
}
