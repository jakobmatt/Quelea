/* 
 * This file is part of Quelea, free projection software for churches.
 * 
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.quelea.services.importexport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.concurrent.Task;
import javafx.scene.layout.VBox;
import org.apache.commons.io.FilenameUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.quelea.data.ThemeDTO;
import org.quelea.data.displayable.*;
import org.quelea.services.languages.LabelGrabber;
import org.quelea.services.utils.FileFilters;
import org.quelea.services.utils.LoggerUtils;
import org.quelea.services.utils.Utils;
import org.quelea.windows.main.QueleaApp;

/**
 *
 * @author Fabian Mathews
 */

    enum PlanType {
//        MEDIA,
        SONG,
//        CUSTOM_SLIDES,
        MEDIA,
        UNKNOWN,
    }

    enum MediaType {
        PRESENTATION,
        PDF,
        VIDEO,
        IMAGE,
        AUDIO,
        CHART,
        LYRICS,
        UNKNOWN,
    }

public class ElvantoPlanDialog extends BorderPane {

    private class AttachedFileType {
        String id;
        String title;
        MediaType mediaType;
        String type;
        boolean html;
        String content;

        public AttachedFileType(String id, String title, String type, MediaType mediaType, String content, boolean html) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.mediaType = mediaType;
            this.content = content;
            this.html = html;
        }
    }

    private class AttachedPlanFileObj extends JSONObject {
        PlanType plantype;
        MediaType mediatype;

        public AttachedPlanFileObj(PlanType plantype, MediaType mediatype) {
            super();
            this.plantype = plantype;
            this.mediatype = mediatype;
        }
    }

    private class AttachedPlanDetails extends AttachedPlanFileObj {
/*
        JSON object layout:
            item.put("html", 0);
            item.put("id", "0");
            item.put("title", title);
            item.put("content", title);
            item.put("plantype", PlanType.MEDIA);
            item.put("mediatype", mediaType);

* */
        boolean html = false;
        String id = "";
        String title = "";
        String content = "";

        public AttachedPlanDetails(PlanType plantype, MediaType mediatype) {
            super(plantype, mediatype);
        }
    }

    private static final Logger LOGGER = LoggerUtils.getLogger();
    private final Map<TreeItem<String>, AttachedPlanFileObj> treeViewItemMap = new HashMap<>();
    private final ElvantoImportDialog importDialog;
    
    private JSONObject  planJSON;
    private JSONArray filesArrayJSON;
    
    @FXML private TreeView planView;
    @FXML private ProgressBar totalProgress;
    @FXML private ProgressBar itemProgress;
    @FXML private VBox buttonBox;
    @FXML private VBox progressBox;
    
    public ElvantoPlanDialog() {
        importDialog = null;
    }

    public ElvantoPlanDialog(ElvantoImportDialog importDlg, JSONObject plan, JSONArray filesArray) {
        importDialog = importDlg;
        planJSON = plan;
        filesArrayJSON = filesArray;
              
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setController(this);
            loader.setResources(LabelGrabber.INSTANCE);
            Parent root = loader.load(getClass().getResourceAsStream("PlanningCenterOnlinePlanDialog.fxml"));
            setCenter(root);
            planView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            enablePlanProgressBars(false);
            LOGGER.log(Level.INFO, "Initialised dialog, updating view");
            updateView();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error", e);
        }       
    }

    protected AttachedFileType getItemFileType(JSONObject item) {
//        item looks like:
//        {"html":0,
//        "id":"4ed37a24-c7e7-4006-b399-7f4b576a8995",
//        "title":"Sinnesro",
//        "type":"Lyrics",
//        "content":"https:\/\/d38zbiv2ku29ka.cloudfront.net\/94871D01\/services\/file_4ed37a24-c7e7-4006-b399-7f4b576a8995_1572012537.txt"}
        AttachedFileType ret;
        try {
            String id = (String)item.get("id");
            String title = (String)item.get("title");
            String content = (String)item.get("content");
            String type = (String)item.get("type");
            MediaType mediaType;
            switch (type) {
                case "Lyrics":
                    mediaType = MediaType.LYRICS;
                    break;
                case "Image":
                    mediaType = MediaType.IMAGE;
                    break;
                case "File":
                    mediaType = ElvantoPlanDialog.classifyMedia(content);
                    break;
                case "Audio":
                    mediaType = MediaType.AUDIO;
                    break;
                case "Video":
                    mediaType = MediaType.VIDEO;
                    break;
                case "Chart":
                    mediaType = MediaType.CHART;
                    break;
                default:
                    mediaType = MediaType.UNKNOWN;
                    break;
            }
            ret = new AttachedFileType(id, title, type, mediaType, content, false);
            LOGGER.log(Level.WARNING, "Type: " + type.toString() + " {" + item + "}");
            return ret;
        }
        catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in reading file from {" + item + "}");
        }

        return null;
    }

    protected PlanType getItemPlanType(JSONObject item) {
        // determine from JSON obj if object is a song
        try {
            JSONObject song = (JSONObject)item.get("song");
            return PlanType.SONG;
        }
        catch (Exception e) {
        }

        return PlanType.UNKNOWN;
    }

    @SuppressWarnings("unchecked")
    protected void updateView() {
        LOGGER.log(Level.INFO, "JSON is {0}", planJSON);
        LOGGER.log(Level.INFO, "JSON files array is {0}", filesArrayJSON);

        planView.setShowRoot(false);
        TreeItem<String> rootTreeItem = new TreeItem<>();
        planView.setRoot(rootTreeItem);

        JSONObject itemsObj = (JSONObject)planJSON.get("items");
        JSONArray itemArray = (JSONArray)itemsObj.get("item");

        for (Object itemObj : itemArray) {
            JSONObject item = (JSONObject)itemObj;
            
            PlanType planType = getItemPlanType(item);
            switch (planType)
            {
/*
                case MEDIA:
                    addToView_PlanMedia(item, rootTreeItem);
                    break;
*/

                case SONG:
                    addToView_PlanSong(item, rootTreeItem);
                    break;
                    
/*
                case CUSTOM_SLIDES:
                    addToView_CustomSlides(item, rootTreeItem);
                    break;
*/

                default:
                    break;
            }
        }

        /* Parse all attached plan files */
        if (filesArrayJSON != null)
        {
            for (Object filesObj : filesArrayJSON) {
                /* At this point, JSONObject only exists, and AttachedPlanFileObj are to be created */
                JSONObject item = (JSONObject)filesObj;

                AttachedFileType planType = getItemFileType(item);
                switch (planType.mediaType)
                {
                    case AUDIO:
                    case IMAGE:
                    case CHART:
                    case VIDEO:
                        addToView_PlanMedia(item, rootTreeItem, planType.mediaType);
                        break;

                    case LYRICS:
                        addToView_PlanLyrics(item, rootTreeItem, planType.mediaType);
                        break;

                    case PDF:
                    case PRESENTATION:
                        addToView_CustomSlides(item, rootTreeItem, planType.mediaType);
                        break;

                    default:
                        break;
                }
            }
        }
    }
    
    protected void addToView_PlanMedia(JSONObject item, TreeItem<String> parentTreeItem, MediaType mediaType) {
        String title = mediaType.toString().toLowerCase() + " ";
        String content = "";
        try {
            title = title + (String)item.get("title");
            content = (String)item.get("content");
            //title = title + " (" + content.substring(content.lastIndexOf(".")) + ") ";
        }
        catch (NullPointerException e)
        {
        }
        AttachedPlanDetails planItem = new AttachedPlanDetails(PlanType.MEDIA, mediaType);
        planItem.html = (Long) (item.get("html")) > 0 ? true : false;
        planItem.id = (String) item.get("id");
        planItem.title = title;
        planItem.content = content;
        TreeItem<String> treeItem = new TreeItem<>(title);
        parentTreeItem.getChildren().add(treeItem);
        treeViewItemMap.put(treeItem, planItem);
    }

    protected void addToView_PlanLyrics(JSONObject item, TreeItem<String> parentTreeItem, MediaType mediaType) {
        String title = mediaType.toString().toLowerCase() + " ";
        String content = "";
        try {
            title = title + (String)item.get("title");
            content = (String)item.get("content");
            //title = title + " (" + content.substring(content.lastIndexOf(".")) + ") ";
        }
        catch (NullPointerException e)
        {
        }
        AttachedPlanDetails planItem = new AttachedPlanDetails(PlanType.MEDIA, mediaType);
        planItem.html = (Long) (item.get("html")) > 0 ? true : false;
        planItem.id = (String) item.get("id");
        planItem.title = title;
        planItem.content = content;
        TreeItem<String> treeItem = new TreeItem<>(title);
        parentTreeItem.getChildren().add(treeItem);
        treeViewItemMap.put(treeItem, planItem);
    }

    protected void addToView_CustomSlides(JSONObject item, TreeItem<String> parentTreeItem, MediaType mediaType) {
        String title = mediaType.toString().toLowerCase() + " ";
        String content = "";
        try {
            title = title + (String)item.get("title");
            content = (String)item.get("content");
            //title = title + " (" + content.substring(content.lastIndexOf(".")) + ") ";
        }
        catch (NullPointerException e)
        {
        }
        AttachedPlanDetails planItem = new AttachedPlanDetails(PlanType.MEDIA, mediaType);
        planItem.html = (Long) (item.get("html")) > 0 ? true : false;
        planItem.id = (String) item.get("id");
        planItem.title = title;
        planItem.content = content;
        TreeItem<String> treeItem = new TreeItem<>(title);
        parentTreeItem.getChildren().add(treeItem);
        treeViewItemMap.put(treeItem, planItem);
    }

    protected void addToView_PlanSong(JSONObject item, TreeItem<String> parentTreeItem) {
        try {
            JSONObject song = (JSONObject)item.get("song");
            String title = "Song: " + (String)song.get("title");
            /* Let song object look like any other attached object */
            AttachedPlanDetails songItem = new AttachedPlanDetails(PlanType.SONG, MediaType.UNKNOWN);
            songItem.html = false;
            songItem.id = "0";
            songItem.title = title;
            songItem.content = title;
            TreeItem<String> treeItem = new TreeItem<>(title);
            parentTreeItem.getChildren().add(treeItem);
            treeViewItemMap.put(treeItem, songItem);
        }
        catch (Exception e) {
            LOGGER.log(Level.WARNING, "Item " + item + " Error ", e);
        }
    }

    @SuppressWarnings("unchecked")
    @FXML private void onImportAllAction(ActionEvent event) {
        List<TreeItem<String> > allTreeItems = (List<TreeItem<String> >)planView.getRoot().getChildren();
        importSelected(allTreeItems);
    }
    
    @SuppressWarnings("unchecked")
    @FXML private void onImportSelectedAction(ActionEvent event) {
        List<TreeItem<String> > selectedTreeItems = (List<TreeItem<String> >)planView.getSelectionModel().getSelectedItems();
        importSelected(selectedTreeItems);
    }

    @SuppressWarnings("unchecked")
    @FXML private void onRefreshAction(ActionEvent event) {
        TreeItem<String> rootTreeItem = new TreeItem<>();
        planView.setRoot(rootTreeItem);
        importDialog.updatePlans();
        updateView();
    }
    
    // Disable/enable appropriate widgets while a import task is in operation
    private void enablePlanProgressBars(boolean enable) {
        buttonBox.setDisable(enable);
        progressBox.setVisible(enable);
        planView.setDisable(enable);
        
        // stop user being able to try to change to another plan and do bad!
        importDialog.enablePlanProgressBars(enable);        
    }

    class ImportTask extends Task<Void> {

        List<TreeItem<String> > selectedTreeItems;
        List<Displayable>       importItems = new ArrayList<>();

        ImportTask(List<TreeItem<String> > selectedTreeItems) {
            this.selectedTreeItems = selectedTreeItems;
        }

        @Override 
        protected Void call() throws Exception {
            enablePlanProgressBars(true);
            totalProgress.setProgress(0);

            int index = 0;
            for (TreeItem<String> treeItem : selectedTreeItems) {
                AttachedPlanFileObj item = treeViewItemMap.get(treeItem);

                itemProgress.setProgress(0);

                PlanType planType = item.plantype;
                switch (planType)
                {
                    case MEDIA:
                        prepare_PlanMedia((AttachedPlanDetails)item, treeItem);
                        break;

                    case SONG:
                        prepare_PlanSong(item, treeItem);
                        break;

                    default:
                        break;
                }

                ++index;                
                totalProgress.setProgress((double)index / (double)selectedTreeItems.size());
            }

            enablePlanProgressBars(false);
            return null;
        }
        
        @Override 
        protected void succeeded() { 
            importTaskSucceeded(this);
            super.succeeded();
        }
        
        protected void prepare_PlanMedia(AttachedPlanDetails item, TreeItem<String> treeItem) {
            MediaType mediatype = item.mediatype;
            // a file to download then put into Quelea
            String url = (String)item.content;

            String fileName = "";
            String titleName = " ";
            try {
                fileName = mediatype.toString() + "-";
                titleName = item.title;
                String idName = item.id;
                String extName = url.substring(url.lastIndexOf("."));
                if (extName.indexOf("/") > 0)
                {
                    extName = extName.substring(0, extName.indexOf("/"));
                }
                fileName += titleName + "-" + idName + extName;
            }
            catch (Exception e)
            {
                LOGGER.log(Level.WARNING, "Error", e);
            }
            // work out when file was last updated in PCO
            Date date = new Date();

            Displayable displayable = null;

            try {
                fileName = importDialog.getParser().downloadFile(url, fileName, itemProgress, date);

                switch (mediatype)
                {
                    case LYRICS:
                        String text = new String(Files.readAllBytes(Paths.get(fileName)), StandardCharsets.UTF_8);
                        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
                        String dateString = format.format(new Date());
                        displayable = new SongDisplayable(dateString + "-" + titleName,"", ThemeDTO.DEFAULT_THEME); //new SongDisplayable("imported-" + fileName, "elvanto");
                        SongDisplayable song = ((SongDisplayable) displayable);

                        song.setCopyright("");
                        song.setCcli("");
                        song.setAuthor("");
                        // double line separator so SongDisplayable knows where to break the slides apart
                        //String joinedSlidesText = String.join(System.lineSeparator() + System.lineSeparator(), slideTextArray);
                        song.setLyrics(text);
                        Utils.updateSongInBackground(song, true, false);
                        //TextSection[] sections = ((SongDisplayable) displayable).getSections();
                        break;

                    case PRESENTATION:
                        try {
                            displayable = new PresentationDisplayable(new File(fileName));
                        }
                        catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error", e);
                        }
                        break;

                    case PDF:
                        try {
                            displayable = new PdfDisplayable(new File(fileName));
                        }
                        catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error", e);
                        }
                        break;

                    case VIDEO:
                        displayable = new VideoDisplayable(fileName);
                        break;

                    case IMAGE:
                    case CHART:
                        displayable = new ImageDisplayable(new File(fileName));
                        break;

                   case  AUDIO:
                        displayable = new AudioDisplayable(new File(fileName));
                        break;

                    default:
                    case UNKNOWN:
                        LOGGER.log(Level.WARNING, "Unknown type of item " + fileName);
                        break;
                }
            }
            catch (Exception e)
            {
                LOGGER.log(Level.WARNING, "Failed to import " + url, e);
            }

            if (displayable != null) {
                importItems.add(displayable);
            }
        }
        
        protected void prepare_PlanSong(AttachedPlanFileObj item, TreeItem<String> treeItem) {
            JSONObject songJSON = (JSONObject)item.get("song");
            String title = (String)songJSON.get("title");
            String author = (String)songJSON.get("artist");

            String arrangementId = (String)((JSONObject)songJSON.get("arrangement")).get("id");
            JSONObject response = importDialog.getParser().arrangement(arrangementId);
            JSONObject arrangement = (JSONObject)((JSONArray)response.get("arrangement")).get(0);
            String lyrics = cleanLyrics((String)arrangement.get("lyrics"));

            String ccli = (String)songJSON.get("ccli_number");
            String copyright = (String)arrangement.get("copyright");

            SongDisplayable song = new SongDisplayable(title, author);
            song.setLyrics(lyrics);
            song.setCopyright(copyright);
            song.setCcli(ccli);     

            Utils.updateSongInBackground(song, true, false);
            importItems.add(song);
        }

        protected void prepare_CustomSlides(JSONObject item, TreeItem<String> treeItem) {
            String title = (String)item.get("title");
            List<TextSection> textSections = new ArrayList<TextSection>();

            List<String> slideTextArray = new ArrayList<String>();
            JSONArray customSlides = (JSONArray)item.get("custom_slides");
            for (Object slideObj : customSlides) {
                JSONObject slide = (JSONObject)slideObj;
                String body = (String)slide.get("body");

                // might need something like this in future:
                // depending on how often we use custom slides with an empty line which I think is rare
                // enough to ignore for now
                //String body = "(" + (String)slide.get("label") + ")" + System.lineSeparator() + (String)slide.get("body");
                slideTextArray.add(body);
            }

            // double line separator so SongDisplayable knows where to break the slides apart
            String joinedSlidesText = String.join(System.lineSeparator() + System.lineSeparator(), slideTextArray);

            SongDisplayable slides = new SongDisplayable(title, "Unknown");
            slides.setLyrics(joinedSlidesText);
            importItems.add(slides);
        }
    };
    
    // This MUST be run in the main thread
    // This adds the prepared displayable items into Quelea
    private void importTaskSucceeded(ImportTask importTask) {
        for (Displayable displayable : importTask.importItems) {
            QueleaApp.get().getMainWindow().getMainPanel().getSchedulePanel().getScheduleList().add(displayable);
        }
        
        QueleaApp.get().getMainWindow().getMainPanel().getPreviewPanel().refresh();
    }
    
    private void importSelected(List<TreeItem<String> > selectedTreeItems) {
        ImportTask task = new ImportTask(selectedTreeItems);
        new Thread(task).start();
    }
    
    static protected MediaType classifyMedia(String fileName) {
        String extension = "*." + FilenameUtils.getExtension(fileName);
        if (FileFilters.POWERPOINT.getExtensions().contains(extension)) {
            return MediaType.PRESENTATION;
        }

        if (FileFilters.AUDIOS.getExtensions().contains(extension)) {
            return MediaType.AUDIO;
        }

        if (FileFilters.PDF_GENERIC.getExtensions().contains(extension)) {
            return MediaType.PDF;
        }
                
        if (FileFilters.VIDEOS.getExtensions().contains(extension)) {
            return MediaType.VIDEO;
        }
        
        if (FileFilters.IMAGES.getExtensions().contains(extension)) {
            return MediaType.IMAGE;
        }

        if (FileFilters.TXT.getExtensions().contains(extension)) {
            return MediaType.LYRICS;
        }

        return MediaType.UNKNOWN;
    }
            
    // clean up things like (C2) transform it to (Chorus 2)
    // so Quelea can handle it
    protected String cleanLyrics(String lyrics) {
        Pattern titleExp = Pattern.compile("^\\(?(Verse|Chorus|Pre-Chorus|Pre Chorus|Tag|Outro|Bridge|Misc|Interlude|Ending)\\)?\\s?(\\d?)|\\(?(\\S)(\\d+)\\)?$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        
        // allows us to expand abbreviations to full name (ensure Key value is all uppercase)
        Map<String, String> titleDict = new HashMap<String, String>();
        titleDict.put("C", "Chorus");
        titleDict.put("PC", "Pre-Chorus");
        titleDict.put("V", "Verse");
        titleDict.put("T", "Tag");
        titleDict.put("O", "Outro");
        titleDict.put("B", "Bridge");
        titleDict.put("M", "Misc");
        titleDict.put("E", "Ending");
        titleDict.put("I", "Interlude");
        
        class TitleTextBlock
        {
            public String title;
            public String text;
            
            public TitleTextBlock(String title, String text) {
                this.title = title;
                this.text = text;
            }
        }
        List<TitleTextBlock> titleTextBlockList = new ArrayList<TitleTextBlock>();
        
       
        // lets clean up some funky stuff we don't want the audience to know about:
        // remove line repat X time tags - (5X) 
        // and (REPEAT) tags
        Pattern removeExp = Pattern.compile("\\(\\d+X\\)|\\(REPEAT\\)", Pattern.CASE_INSENSITIVE);
        Matcher m = removeExp.matcher(lyrics);
        lyrics = m.replaceAll("").trim();
        
        // remove embedded choords (wrapped in brackets)
        Pattern removeChoordsExp = Pattern.compile("(?m)(^| |\\[|\\b)([A-G](##?|bb?)?((sus|maj|min|aug|dim)\\d?)?(\\/[A-G](##?|bb?)?)?)(\\]| (?!\\w)|$)");
        Matcher m2 = removeChoordsExp.matcher(lyrics);
        lyrics = m2.replaceAll("");
        
        int lastMatchEnd = -1;
        String lastTitle = "";
        Matcher match = titleExp.matcher(lyrics);        
        while (match.find()) {            
            try {
                int groupCount = match.groupCount();
                String title = (match.group(1) != null) ? match.group(1) : "";
                title = (match.group(3) != null) ? match.group(3) : title;
                if (!title.isEmpty()) {
                    // expand abbreviations
                    if (titleDict.containsKey(title.toUpperCase())) {
                        title = titleDict.get(title);
                    }
                }
				
                String number =  (match.group(2) != null) ? match.group(2) : "";
                number = (match.group(4) != null) ? match.group(4) : number;

                title = title + " " + number;
                title = title.trim();

				int matchStart = match.start();
                if (lastMatchEnd != -1) {
                    String text = lyrics.substring(lastMatchEnd, matchStart).trim();
                    titleTextBlockList.add(new TitleTextBlock(lastTitle, text));
                }
				else {
					// if the first title is malformed, at least this will pull down the text for the user to be able to fix it up
					if (matchStart != 0) {
						String text = lyrics.substring(0, matchStart).trim();
						titleTextBlockList.add(new TitleTextBlock("Unknown", text));
					}
				}
                
                lastTitle = title;
            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error", e);
            }
            
            lastMatchEnd = match.end();
        }
        
        if (lastMatchEnd != -1) {
            String text = lyrics.substring(lastMatchEnd).trim();
            titleTextBlockList.add(new TitleTextBlock(lastTitle, text));
        }
		else {
			// the whole song is malformed, at least this will pull down the text for the user to be able to fix it up
			String text = lyrics;
			titleTextBlockList.add(new TitleTextBlock("Unknown", text));
		}
        
        // now the song has been divided into titled text blocks, time to bring it together nicely
        // for Quelea
        String cleanedLyrics = "";
        for (int i = 0; i < titleTextBlockList.size(); ++i) {
            TitleTextBlock titleTextBlock = titleTextBlockList.get(i);
            if (titleTextBlock.text.isEmpty()) {
                continue;
            }
            
            // newlines separating previous from current
            if (i != 0) {
                cleanedLyrics += System.lineSeparator() + System.lineSeparator();
            }
            
            cleanedLyrics += "(" + titleTextBlock.title + ")" + System.lineSeparator() + titleTextBlock.text;
         }

        return cleanedLyrics;
    }
}
