package com.structurizr.cli;

import com.structurizr.Workspace;
import com.structurizr.dsl.StructurizrDslFormatter;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.io.ilograph.IlographWriter;
import com.structurizr.io.mermaid.MermaidDiagram;
import com.structurizr.io.mermaid.MermaidWriter;
import com.structurizr.io.plantuml.*;
import com.structurizr.io.websequencediagrams.WebSequenceDiagramsWriter;
import com.structurizr.util.WorkspaceUtils;
import com.structurizr.view.DynamicView;
import com.structurizr.view.ThemeUtils;
import org.apache.commons.cli.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;

class ExportCommand extends AbstractCommand {

    private static final String JSON_FORMAT = "json";
    private static final String DSL_FORMAT = "dsl";
    private static final String PLANTUML_FORMAT = "plantuml";
    private static final String PLANTUML_C4PLANTUML_SUBFORMAT = "c4plantuml";
    private static final String PLANTUML_BASIC_SUBFORMAT = "basic";
    private static final String PLANTUML_STRUCTURIZR_SUBFORMAT = "structurizr";
    private static final String WEBSEQUENCEDIAGRAMS_FORMAT = "websequencediagrams";
    private static final String MERMAID_FORMAT = "mermaid";
    private static final String ILOGRAPH_FORMAT = "ilograph";

    ExportCommand(String version) {
        super(version);
    }

    void run(String... args) throws Exception {
        Options options = new Options();

        Option option = new Option("w", "workspace", true, "Path or URL to the workspace JSON file/DSL file(s)");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("f", "format", true, String.format("Export format: %s[/%s|%s|%s]|%s|%s|%s|%s|%s", PLANTUML_FORMAT, PLANTUML_STRUCTURIZR_SUBFORMAT, PLANTUML_BASIC_SUBFORMAT, PLANTUML_C4PLANTUML_SUBFORMAT, WEBSEQUENCEDIAGRAMS_FORMAT, MERMAID_FORMAT, ILOGRAPH_FORMAT, JSON_FORMAT, DSL_FORMAT));
        option.setRequired(true);
        options.addOption(option);

        option = new Option("o", "output", true, "Path to an output directory");
        option.setRequired(false);
        options.addOption(option);

        CommandLineParser commandLineParser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        String workspacePathAsString = null;
        File workspacePath = null;
        long workspaceId = 1;
        String format = "";
        String outputPath = null;

        try {
            CommandLine cmd = commandLineParser.parse(options, args);

            workspacePathAsString = cmd.getOptionValue("workspace");
            format = cmd.getOptionValue("format").toLowerCase();
            outputPath = cmd.getOptionValue("output");

        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.setWidth(150);
            formatter.printHelp("export", options);

            System.exit(1);
        }

        Workspace workspace;

        System.out.println("Exporting workspace from " + workspacePathAsString);

        if (workspacePathAsString.endsWith(".json")) {
            System.out.println(" - loading workspace from JSON");

            if (workspacePathAsString.startsWith("http://") || workspacePathAsString.startsWith("https")) {
                String json = readFromUrl(workspacePathAsString);
                workspace = WorkspaceUtils.fromJson(json);
                workspacePath = new File(".");
            } else {
                workspacePath = new File(workspacePathAsString);
                workspace = WorkspaceUtils.loadWorkspaceFromJson(workspacePath);
            }
            
        } else {
            System.out.println(" - loading workspace from DSL");
            StructurizrDslParser structurizrDslParser = new StructurizrDslParser();

            if (workspacePathAsString.startsWith("http://") || workspacePathAsString.startsWith("https")) {
                String dsl = readFromUrl(workspacePathAsString);
                structurizrDslParser.parse(dsl);
                workspacePath = new File(".");
            } else {
                workspacePath = new File(workspacePathAsString);
                structurizrDslParser.parse(workspacePath);
            }

            workspace = structurizrDslParser.getWorkspace();
        }

        workspaceId = workspace.getId();

        if (!JSON_FORMAT.equalsIgnoreCase(format) && !DSL_FORMAT.equalsIgnoreCase(format)) {
            // only inline the theme amd create default views if the user wants a diagram export
            ThemeUtils.loadThemes(workspace);
            addDefaultViewsAndStyles(workspace);
        }

        if (outputPath == null) {
            outputPath = new File(workspacePath.getCanonicalPath()).getParent();
        }
        
        File outputDir = new File(outputPath);
        outputDir.mkdirs();

        if (JSON_FORMAT.equalsIgnoreCase(format)) {
            String filename = workspacePath.getName().substring(0, workspacePath.getName().lastIndexOf('.'));
            File file = new File(outputPath, String.format("%s.json", filename));
            System.out.println(" - writing " + file.getCanonicalPath());
            WorkspaceUtils.saveWorkspaceToJson(workspace, file);
        } else if (DSL_FORMAT.equalsIgnoreCase(format)) {
            String filename = workspacePath.getName().substring(0, workspacePath.getName().lastIndexOf('.'));
            File file = new File(outputPath, String.format("%s.dsl", filename));

            StructurizrDslFormatter structurizrDslFormatter = new StructurizrDslFormatter();
            String dsl = structurizrDslFormatter.format(WorkspaceUtils.toJson(workspace, false));

            writeToFile(file, dsl);
        } else if (format.startsWith(PLANTUML_FORMAT)) {
            PlantUMLWriter plantUMLWriter = null;

            String[] tokens = format.split("/");
            String subformat = PLANTUML_STRUCTURIZR_SUBFORMAT;
            if (tokens.length == 2) {
                subformat = tokens[1];
            }

            switch (subformat) {
                case PLANTUML_C4PLANTUML_SUBFORMAT:
                    plantUMLWriter = new C4PlantUMLWriter();
                    break;
                case PLANTUML_BASIC_SUBFORMAT:
                    plantUMLWriter = new BasicPlantUMLWriter();
                    break;
                case PLANTUML_STRUCTURIZR_SUBFORMAT:
                    plantUMLWriter = new StructurizrPlantUMLWriter();
                    break;
                default:
                    System.out.println(" - unknown PlantUML subformat: " + subformat);
                    System.exit(1);
            }

            System.out.println(" - using " + plantUMLWriter.getClass().getSimpleName());

            if (workspace.getViews().isEmpty()) {
                System.out.println(" - the workspace contains no views");
            } else {
                plantUMLWriter.setUseSequenceDiagrams(false);
                Collection<PlantUMLDiagram> diagrams = plantUMLWriter.toPlantUMLDiagrams(workspace);

                for (PlantUMLDiagram diagram : diagrams) {
                    File file = new File(outputPath, String.format("%s-%s.puml", prefix(workspaceId), diagram.getKey()));
                    writeToFile(file, diagram.getDefinition());
                }

                plantUMLWriter.setUseSequenceDiagrams(true);
                for (DynamicView dynamicView : workspace.getViews().getDynamicViews()) {
                    String definition = plantUMLWriter.toString(dynamicView);

                    File file = new File(outputPath, String.format("%s-%s-sequence.puml", prefix(workspaceId), dynamicView.getKey()));
                    writeToFile(file, definition);
                }
            }
        } else if (MERMAID_FORMAT.equalsIgnoreCase(format)) {
            if (workspace.getViews().isEmpty()) {
                System.out.println(" - the workspace contains no views");
            } else {
                MermaidWriter mermaidWriter = new MermaidWriter();
                mermaidWriter.setUseSequenceDiagrams(false);
                Collection<MermaidDiagram> diagrams = mermaidWriter.toMermaidDiagrams(workspace);

                for (MermaidDiagram diagram : diagrams) {
                    File file = new File(outputPath, String.format("%s-%s.mmd", prefix(workspaceId), diagram.getKey()));
                    writeToFile(file, diagram.getDefinition());
                }

                mermaidWriter.setUseSequenceDiagrams(true);
                for (DynamicView dynamicView : workspace.getViews().getDynamicViews()) {
                    String definition = mermaidWriter.toString(dynamicView);

                    File file = new File(outputPath, String.format("%s-%s-sequence.mmd", prefix(workspaceId), dynamicView.getKey()));
                    writeToFile(file, definition);
                }
            }
        } else if (WEBSEQUENCEDIAGRAMS_FORMAT.equalsIgnoreCase(format)) {
            WebSequenceDiagramsWriter webSequenceDiagramsWriter = new WebSequenceDiagramsWriter();
            if (workspace.getViews().getDynamicViews().isEmpty()) {
                System.out.println(" - the workspace contains no dynamic views");
            } else {
                for (DynamicView dynamicView : workspace.getViews().getDynamicViews()) {
                    String definition = webSequenceDiagramsWriter.toString(dynamicView);
                    File file = new File(outputPath, String.format("%s-%s.wsd", prefix(workspaceId), dynamicView.getKey()));
                    writeToFile(file, definition);
                }
            }
        } else if (ILOGRAPH_FORMAT.equalsIgnoreCase(format)) {
            String ilographDefinition = new IlographWriter().toString(workspace);
            File file = new File(outputPath, String.format("%s.idl", prefix(workspaceId)));
            writeToFile(file, ilographDefinition);
        } else {
            System.out.println(" - unknown output format: " + format);
        }

        System.out.println(" - finished");
    }

    private String prefix(long workspaceId) {
        if (workspaceId > 0) {
            return "structurizr-" + workspaceId;
        } else {
            return "structurizr";
        }
    }

    private void writeToFile(File file, String content) throws Exception {
        System.out.println(" - writing " + file.getCanonicalPath());

        BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        writer.write(content);
        writer.close();
    }

}