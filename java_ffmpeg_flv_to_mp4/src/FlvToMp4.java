
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import MediaOperations.FormatConverter;
import MediaOperations.FormatConverterWithTranscoding;

/* FlvToMp4 : Converts flv with mp4 without transcoding.
 * Testcases :
 * 1. flv with H264/AAC codec
 *      Expectation : Should convert file correctly.
 *      Output : Passed.
 * 2. flv with H264 and no audio.
 *      Expectation : Should convert file correctly.
 *      Output : Passed.
 * 3. flv with H264 and mp3.
 *      Expectation : Should convert file correctly.
 *      Output : Passed.
 * 4. flv with flv and any.
 *      Expectation : Conversion should fail with appropriate error.
 *      Output : Passed.
 * 5. Lip sync.
 *      Expectation : Lip sync should be maintained after conversion.
 *      Output : Not yet tested.
 * 6. Bulk conversion 100,000 files. Robustness test.
 *      Expectation : Should convert 100,000 files without exceptions or memory leak
 *      Output : Not yet tested.
 * 7. flv with h264 and any codec other than mp3 and AAC.
 *      Expectation : Conversion should fail with appropriate error.
 *      Output : Not yet tested.
 */
public class FlvToMp4 {
    public static Set<String> listFilesUsingDirectoryStream(String dir) throws IOException {
        Set<String> fileList = new HashSet<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(dir))) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    fileList.add(path.getFileName()
                        .toString());
                }
            }
        }
        return fileList;
    }
    public static void main(String[] args) {

        /* As the problem is not asking for transcoding
         * We will assume that the codecs are compatible.
         *
         * The original file was downloaded from https://www.mediacollege.com/adobe/flash/video/tutorial/example-flv.html
         * but, because it had vp6 codec, not supported by mp4 container,
         * Used ffmpeg to convert the file to flv format with
         * H264/AAC audio. These formats are supported by mp4.
         */

        /* Data downloaded from
         * https://www.learningcontainer.com/mp4-sample-video-files-download/#google_vignette
         * https://filesamples.com/formats/flv
         */
        try {
            Set<String> fileList  = FlvToMp4.listFilesUsingDirectoryStream(args[0]);
            Iterator<String> it = fileList.iterator();
            while(it.hasNext()){
               String filename = args[0] + "\\" + it.next();
               FormatConverter c = new FormatConverter(filename, args[1], "mp4");
               if (c.init()) {
                   boolean result = c.convert();
                   if (result == false) {
                       System.out.println("Failed to convert file  = " + filename);
                   }
               } else {
                   System.out.println("Failed to initialise conversion. file  = " + filename);
               }
            }


        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

}
