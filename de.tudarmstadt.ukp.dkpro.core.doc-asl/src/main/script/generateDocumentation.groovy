#!/usr/bin/env groovy

import static groovy.io.FileType.FILES;
import groovy.json.*;
import groovy.transform.Field;
import static org.apache.uima.UIMAFramework.getXMLParser;
import static org.apache.uima.fit.factory.ResourceCreationSpecifierFactory.*;
import static org.apache.uima.util.CasCreationUtils.mergeTypeSystems;

import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.XMLInputSource;

@Field def engines = [:];

@Field def formats = [:];

@Field def typesystems = [];

@Field def models = [];

def locatePom(path) {
    def pom = new File(path, "pom.xml");
    if (pom.exists()) {
        return pom;
    }
    else if (path.getParentFile() != null) {
        return locatePom(path.getParentFile());
    }
    else {
        return null;
    }
}

def addFormat(format, kind, pom, spec, clazz) {
    if (!formats[format]) {
        formats[format] = [
            groupId: pom.groupId ? pom.groupId.text() : pom.parent.groupId.text(),
            artifactId: pom.artifactId.text(),
            version: pom.version ? pom.version.text() : pom.parent.version.text(),
            pom: pom
        ];
    }
    formats[format][kind+'Class'] = clazz;
    formats[format][kind+'Spec'] = spec;
}

def roleNames = [
    coref: 'Coreference resolver',
    tagger: 'Part-of-speech tagger',
    parser: 'Parser',
    chunker: 'Chunker',
    segmenter: 'Segmenter',
    normalizer: 'Normalizer',
    checker: 'Checker',
    lemmatizer: 'Lemmatizer',
    srl: 'Semantic role labeler',
    morph: 'Morphological analyzer',
    transformer: 'Transformer',
    stem: 'Stemmer',
    ner: 'Named Entity Recognizer',
    langdetect: 'Language Identifier',
    other: 'Other' ];

def getTool(componentName, spec) {
    def outputs = spec.analysisEngineMetaData?.capabilities?.collect { 
        it.outputs?.collect { it.name } }.flatten().sort().unique()
    
    switch (componentName) {
    case { 'de.tudarmstadt.ukp.dkpro.core.api.coref.type.CoreferenceChain' in outputs }: 
        return "coref";
    case { 'de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity' in outputs }: 
        return "ner";
    case { 'de.tudarmstadt.ukp.dkpro.core.api.anomaly.type.GrammarAnomaly' in outputs ||
           'de.tudarmstadt.ukp.dkpro.core.api.anomaly.type.SpellingAnomaly' in outputs }: 
        return "checker";
    case { 'de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.morph.MorphologicalFeatures' in outputs }: 
        return "morph";
    case { 'de.tudarmstadt.ukp.dkpro.core.api.semantics.type.SemanticArgument' in outputs }: 
        return "srl";
    case { 'de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Stem' in outputs }: 
        return "stem";
    case { it.endsWith("Transformer") || it.endsWith("Normalizer") }: 
        return "transformer";
    case { it.endsWith("Chunker") }: 
        return "chunker";
    case { it.endsWith("LanguageIdentifier") || it.contains("LanguageDetector") }: 
        return "langdetect";
    case { it.endsWith("Tagger") }: 
        return "tagger";
    case { it.endsWith("Parser") }: 
        return "parser";
    case { it.endsWith("Segmenter") }: 
        return "segmenter";
    case { it.endsWith("Normalizer") }: 
        return "normalizer";
    case { it.endsWith("Lemmatizer") }: 
        return "lemmatizer";
    default:
        return "other";
    }
}

new File(properties['baseDir'], '..').eachFileRecurse(FILES) {
    if (
        it.name.endsWith('.xml') && 
        (
            // For the typesystem descriptors
            it.path.contains('/src/main/resources/') || 
            // For the analysis engine and reader descriptions
            it.path.contains('/target/classes/')
        )
    ) {
        def processed = false;
        try {
            def spec = createResourceCreationSpecifier(it.path, null);
            if (spec instanceof AnalysisEngineDescription) {
                // println "AE " + it;
                def implName = spec.annotatorImplementationName;
                def uniqueName = implName.substring(implName.lastIndexOf('.')+1);
                def pomFile = locatePom(it);
                def pom = new XmlParser().parse(pomFile);

                if (!implName.contains('$')) {
                    if (implName.endsWith('Writer')) {
                        def format = uniqueName[0..-7];
                        addFormat(format, 'writer', pom, spec, spec.annotatorImplementationName);
                    }
                    else {
                        engines[uniqueName] = [
                            name: uniqueName,
                            groupId: pom.groupId ? pom.groupId.text() : pom.parent.groupId.text(),
                            artifactId: pom.artifactId.text(),
                            version: pom.version ? pom.version.text() : pom.parent.version.text(),
                            pom: pom,
                            spec: spec,
                            role: roleNames[getTool(uniqueName, spec)],
                            tool: getTool(uniqueName, spec)
                        ];
                    }
                }
            }
            else if (spec instanceof CollectionReaderDescription) {
                def implName = spec.implementationName;
                if (implName.endsWith('Reader') && !implName.contains('$')) {
                    def uniqueName = implName.substring(implName.lastIndexOf('.')+1);
                    def pomFile = locatePom(it);
                    def pom = new XmlParser().parse(pomFile);
                    def format = uniqueName[0..-7];
                    addFormat(format, 'reader', pom, spec, implName);
                }
            }
            else {
                // println "?? " + it;
            }
            processed = true;
        }
        catch (org.apache.uima.util.InvalidXMLException e) {
            // Ignore
        }
        
        if (!processed) {
            try {
                typesystems << getXMLParser().parseTypeSystemDescription(
                    new XMLInputSource(it.path));
                processed = true;
            }
            catch (org.apache.uima.util.InvalidXMLException e) {
                // Ignore
            }
        }
    }
}

new File(properties['baseDir'], '..').eachFileRecurse(FILES) {
    if (it.path.endsWith('/src/scripts/build.xml')) {
        def buildXml = new XmlSlurper().parse(it);
        def modelXmls = buildXml.'**'.findAll{ node -> node.name() in [
            'install-stub-and-upstream-file', 'install-stub-and-upstream-folder',
            'install-upstream-file', 'install-upstream-folder' ]};
        
        // Extrack package
        def pack = buildXml.'**'.find { it.name() == 'property' && it.@name == 'outputPackage' }.@value as String;
        if (pack.endsWith('/')) {
            pack = pack[0..-2];
        }
        if (pack.endsWith('/lib')) {
            pack = pack[0..-5];
        }
        pack = pack.replaceAll('/', '.');
        
        // Auto-generate some additional attributes for convenience!
        modelXmls.each { model ->
            def shortBase = model.@artifactIdBase.text().tokenize('.')[-1];
            model.@shortBase = shortBase as String;
            model.@shortArtifactId = "${shortBase}-model-${model.@tool}-${model.@language}-${model.@variant}" as String;
            model.@artifactId = "${model.@artifactIdBase}-model-${model.@tool}-${model.@language}-${model.@variant}" as String;
            model.@package = pack as String;
            model.@version = "${model.@upstreamVersion}.${model.@metaDataVersion}" as String;
            
            def engine = engines.values()
                .findAll { engine ->
                    def clazz = engine.spec.annotatorImplementationName;
                    def enginePack = clazz.substring(0, clazz.lastIndexOf('.'));
                    enginePack == pack;
                }
                .find { engine ->
                    // There should be only one tool matching here - at least we don't have models
                    // yet that apply to multiple tools... I believe - REC
                    switch (model.@tool as String) {
                    case 'token':
                        return engine.tool == 'segmenter';
                    case 'sentence':
                        return engine.tool == 'segmenter';
                    // Special handling for langdetect models which use wrong tool designation
                    case 'languageidentifier':
                        return engine.tool == 'langdetect';
                    // Special handling for MateTools models which use wrong tool designation
                    case 'morphtagger':
                        return engine.tool == 'morph';
                    // Special handling for ClearNLP lemmatizer because dictionary is actually
                    // used in multiple places
                    case 'dictionary':
                        return engine.tool == 'lemmatizer';
                    default:
                        return engine.tool == (model.@tool as String);
                    }
                };
            if (engine) {
                model.@engine = engine.name;
            }
            
        }
        models.addAll(modelXmls);
    }
}

models = models.sort { a,b ->
    (a.@language as String) <=> (b.@language as String) ?: 
    (a.@tool as String) <=> (b.@tool as String) ?: 
    (a.@engine as String) <=> (b.@engine as String) ?: 
    (a.@variant as String) <=> (b.@variant as String)  
}; 
    
def inputOutputTypes = [];
engines.each {
    it.value.spec.analysisEngineMetaData?.capabilities?.each { capability ->
        capability?.inputs.each { inputOutputTypes << it.name};
        capability?.outputs.each { inputOutputTypes << it.name};
    }
}
inputOutputTypes = inputOutputTypes.sort().unique();


def te = new groovy.text.SimpleTemplateEngine();
new File("${properties['baseDir']}/src/main/script/templates/").eachFile(FILES) { tf ->
    def template = te.createTemplate(tf.getText("UTF-8"));
    def result = template.make([
        engines: engines,
        formats: formats,
        models: models,
        typesystems: typesystems,
        inputOutputTypes: inputOutputTypes]);
    def output = new File("${properties['baseDir']}/target/generated-adoc/${tf.name}");
    output.parentFile.mkdirs();
    output.setText(result.toString(), 'UTF-8');
}
