/* This file is part of the ili2fme project.
 * For more information, please see <http://www.eisenhutinformatik.ch/interlis/ili2fme/>.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package ch.interlis.ili2fme;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;

import COM.safe.fme.pluginbuilder.IFMEReader;
import COM.safe.fmeobjects.IFMEFeature;
import COM.safe.fme.pluginbuilder.IFMEMappingFile;
import COM.safe.fmeobjects.IFMEArea;
import COM.safe.fmeobjects.IFMEGeometry;
import COM.safe.fmeobjects.IFMESession;
import COM.safe.fmeobjects.FMEException;
import COM.safe.fmeobjects.IFMELogFile;
import COM.safe.fmeobjects.IFMEFactoryPipeline;
import COM.safe.fmeobjects.IFMEStringArray;
import COM.safe.fmeobjects.IFMEPoint;
import COM.safe.fmeobjects.IFMEPath;
import COM.safe.fmeobjects.IFMEDonut;
import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.tools.StringUtility;
import ch.ehi.fme.*;
import ch.interlis.ili2c.metamodel.*;
import ch.interlis.ilirepository.impl.RepositoryAccessException;
//import ch.interlis.iom.swig.iom_javaConstants;
import ch.interlis.iom.*;
import ch.interlis.iox.*;
import ch.interlis.iom_j.itf.ItfReader;
import ch.interlis.iom_j.itf.ItfReader2;
import ch.interlis.iom_j.xtf.XtfStartTransferEvent;
import ch.interlis.iom_j.xtf.XtfUtility;
import ch.interlis.iox_j.IoxInvalidDataException;
import ch.interlis.iox_j.jts.Iox2jts;
import ch.interlis.iox_j.jts.Iox2jtsException;

/** INTERLIS implementation of an FME-Reader.
 * @author ce
 */
/**
 * @author ceis
 *
 */
public class Ili2Reader implements IFMEReader {
	private IFMEMappingFile mappingFile=null;
	private IFMESession session=null;
	private boolean doPipeline=false;
	private HashMap surfaceBuilders=null; // map<ViewableWrapper, IFMEFactoryPipeline>
	private ArrayList<IoxInvalidDataException> dataerrs = null;
	private String readerKeyword=null;
	private ch.interlis.ili2c.metamodel.TransferDescription iliTd=null;
	private ArrayList<String> iliModelv=null;
	private HashMap tag2class=null; // map<String iliQName,Viewable|AttributeDef modelele>
	
	private HashMap transferViewables=null; // map<String iliQName, ViewableWrapper>
	private Iterator transferViewablei=null;
	private HashSet seenFmeTypes=null;  // set<ViewableWrapper>
	private IFMEFeature pendingSchemaFeature=null;
	private boolean skipBasket=false;
	private HashSet topicFilterv=null;
	private int formatFeatureTypeIdx=FORMAT_FEATURETYPE_XTFTRANSFER;
	private static final int FORMAT_FEATURETYPE_XTFTRANSFER = 0;
	private static final int FORMAT_FEATURETYPE_XTFBASKETS = 1;
	private static final int FORMAT_FEATURETYPE_XTFDELETEOBJECT = 2;
	private static final int FORMAT_FEATURETYPE_XTFERRORS = 3;
	private static final int FORMAT_FEATURETYPE_ENUMS = 4;
	private String xtfFile=null; // null if ili-file given
	private IoxReader ioxReader=null;
	private java.io.InputStream inputFile=null;
	private String currentBid=null;
	private int iliQNameSize=0;
	private int formatMode=0;
	private static final int MODE_XTF=1;
	private static final int MODE_ITF=2;
	private static final int MODE_ITF2=5;
	private boolean createLineTableFeatures=false;
	private boolean skipPolygonBuilding=false;
	private boolean ili1AddDefVal=false;
	private boolean doRichGeometry=false;
	private int  inheritanceMapping=InheritanceMapping.SUPERCLASS;
	private String ili1ConvertSurface=null;
	private String ili1ConvertArea=null;
	private boolean ili1EnumAsItfCode=false;
	private int createEnumTypes=CreateEnumFeatureTypes.NO; 
	private boolean checkUniqueOid=false;
	private boolean ili1CheckPolygonBuilding=false;
	private boolean checkAttrType=false;
	private boolean checkAttrMultiplicity=false;
	private boolean trimValues=true;
	private boolean ili1RenumberTid=false;
	private GeometryConverter geomConv=null;
	private int geometryEncoding=GeometryEncoding.OGC_HEXBIN;
	private int geomAttrMapping=GeomAttrMapping.ENCODE_AS_ATTRIBUTE;
	private IFMELogFile fmeLog=null;
	private static final String ERR_FEATURETYPE_PREFIX="ERR.";
	private static final String ERRMSG_ATTRIBUTE="_errmsg";
	public Ili2Reader(IFMESession session1,IFMEMappingFile mappingFile1,String keyword,IFMELogFile log){
		mappingFile=mappingFile1;
		readerKeyword=keyword;
		session=session1;
		fmeLog=log;
	}
	public void open(ArrayList args) throws Exception {
		listener=Main.setupLogging(fmeLog);
		try{
			myopen(args);
		}catch(Exception ex)
		{
			EhiLogger.logError(ex);
			throw ex;
		}
	}
	public void myopen(ArrayList args) throws Exception {
		if(args.size()==0){
			throw new IllegalArgumentException("args.size()==0");
		}
		//EhiLogger.getInstance().setTraceFilter(false);
		// setup logging of trace messages
		{
			for(int i=0;i<args.size();i++){
				String arg=(String)args.get(i);
				//EhiLogger.traceState("arg["+Integer.toString(i)+"] "+arg);
				if(arg.equals(Main.TRACEMSGS)){
					i++;
					EhiLogger.getInstance().setTraceFilter(!FmeUtility.isTrue((String)args.get(i)));
					break;
				}else{
					// skip this argument
				}
			}
			Iterator elei=mappingFile.elements();
			while(elei.hasNext()){
				Object eleo=elei.next();
				if(eleo instanceof ArrayList){
					ArrayList ele=(ArrayList)eleo;
					String val=(String)ele.get(0);
					if(val.equals(readerKeyword+"_"+Main.TRACEMSGS)){
						EhiLogger.getInstance().setTraceFilter(!FmeUtility.isTrue((String)ele.get(1)));
						break;
					}
				}
			}
		}
		//EhiLogger.debug("fmeBuildNumber "+session.fmeBuildNumber());
		//EhiLogger.debug("fmeVersion "+session.fmeVersion());
		int fme_buildnr=session.fmeBuildNumber();
		doRichGeometry=false;
		if(fme_buildnr>=5608){
			IFMEStringArray settings=session.createStringArray();
			session.getSettings("FME_GEOMETRY_HANDLING",settings);

			int entc=settings.entries();
			if(entc>=1){ // FME2011 FME Data Inspector provides multiple entries
				String fmeUseRichGeometry=settings.getElement(0);
				if(fmeUseRichGeometry.equals("Enhanced")){
					doRichGeometry=true;
				}
			}
		}else{
			IFMEStringArray settings=session.createStringArray();
			session.getSettings("FME_USE_RICH_GEOMETRY",settings);

			int entc=settings.entries();
			if(entc==1){
				String fmeUseRichGeometry=settings.getElement(0);
				if(fmeUseRichGeometry.equals("yes")){
					doRichGeometry=true;
				}
			}
		}
		if(!doRichGeometry){
			EhiLogger.logState("ili2fme reader uses classic geometry handling");
		}else{
			EhiLogger.logState("ili2fme reader uses enhanced geometry handling");
		}
		if(false){
			//session.logSettings("DUMP_CONFIG");
			//session.getProperties("*",settings);
			//session.getAllProperties(settings);
			//Iterator dli=mappingFile.fetchDefLines();
			//while(dli.hasNext()){
			//	EhiLogger.debug("defLine "+ dli.next());
			//}
			//String models=mappingFile.fetchString(writerKeyword+"_"+Main.MODELS);
			//EhiLogger.debug("fetch models <"+models+">");
		}
		EhiLogger.traceState("readerKeyword <"+readerKeyword+">");
		String httpProxyHost=null;
		String httpProxyPort=null;
		String models=null;
		String modeldir=null;
		String topicsFilter=null;
		for(int i=0;i<args.size();i++){
			String arg=(String)args.get(i);
			EhiLogger.traceState("arg["+Integer.toString(i)+"] "+arg);
			if(arg.equals(Main.MODELS)){
				i++;
				models=(String)args.get(i);
			}else if(arg.equals(Main.MODEL_DIR)){
				i++;
				modeldir=(String)args.get(i);
			}else if(arg.equals(Main.TOPICS_FILTER)){
				i++;
				topicsFilter=(String)args.get(i);
			}else if(arg.equals(Main.CREATE_LINETABLES)){
				i++;
				createLineTableFeatures=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.SKIP_POLYGONBUILDING)){
				i++;
				skipPolygonBuilding=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.ILI1_ADDDEFVAL)){
				i++;
				ili1AddDefVal=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.ILI1_CONVERTAREA)){
				i++;
				ili1ConvertArea=(String)args.get(i);
			}else if(arg.equals(Main.ILI1_CONVERTSURFACE)){
				i++;
				ili1ConvertSurface=(String)args.get(i);
			}else if(arg.equals(Main.ILI1_CHECKCONVERT)){
				i++;
				ili1CheckPolygonBuilding=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.CHECK_ATTRTYPE)){
				i++;
				checkAttrType=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.CHECK_ATTRMULTIPLICITY)){
				i++;
				checkAttrMultiplicity=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.TRIM_VALUES)){
				i++;
				trimValues=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.ILI1_ENUMASITFCODE)){
				i++;
				ili1EnumAsItfCode=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.INHERITANCE_MAPPING)){
				i++;
				inheritanceMapping=InheritanceMapping.valueOf((String)args.get(i));
			}else if(arg.equals(Main.CREATEFEATURETYPE4ENUM )){
				i++;
				createEnumTypes=CreateEnumFeatureTypes.valueOf((String)args.get(i));
			}else if(arg.equals(Main.GEOMETRY_ENCODING )){
				i++;
				geometryEncoding=GeometryEncoding.valueOf((String)args.get(i));
			}else if(arg.equals(Main.GEOM_ATTR_MAPPING )){
				i++;
				geomAttrMapping=GeomAttrMapping.valueOf((String)args.get(i));
			}else if(arg.equals(Main.CHECK_UNIQUEOID)){
				i++;
				checkUniqueOid=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.ILI1_RENUMBERTID)){
				i++;
				ili1RenumberTid=FmeUtility.isTrue((String)args.get(i));
			}else if(arg.equals(Main.HTTP_PROXYHOST)){
				i++;
				httpProxyHost=(String)args.get(i);
			}else if(arg.equals(Main.HTTP_PROXYPORT)){
				i++;
				httpProxyPort=(String)args.get(i);
			}else{
				// skip this argument
			}
		}
		Iterator elei=mappingFile.elements();
		while(elei.hasNext()){
			Object eleo=elei.next();
			EhiLogger.traceState("element "+eleo.getClass()+","+eleo);
			if(eleo instanceof ArrayList){
				ArrayList ele=(ArrayList)eleo;
				String val=(String)ele.get(0);
				if(val.equals(readerKeyword+"_"+Main.MODELS)){
					models=(String)ele.get(1);	
				}else if(val.equals(readerKeyword+"_"+Main.MODEL_DIR)){
					modeldir=(String)ele.get(1);	
				}else if(val.equals(readerKeyword+"_"+Main.TOPICS_FILTER)){
					topicsFilter=(String)ele.get(1);	
				}else if(val.equals(readerKeyword+"_"+Main.CREATE_LINETABLES)){
					createLineTableFeatures=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.SKIP_POLYGONBUILDING)){
					skipPolygonBuilding=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.ILI1_ADDDEFVAL)){
					ili1AddDefVal=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.ILI1_CONVERTAREA)){
					ili1ConvertArea=StringUtility.purge((String)ele.get(1));	
				}else if(val.equals(readerKeyword+"_"+Main.ILI1_CONVERTSURFACE)){
					ili1ConvertSurface=StringUtility.purge((String)ele.get(1));	
				}else if(val.equals(readerKeyword+"_"+Main.ILI1_CHECKCONVERT)){
					ili1CheckPolygonBuilding=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.CHECK_ATTRTYPE)){
					checkAttrType=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.CHECK_ATTRMULTIPLICITY)){
					checkAttrMultiplicity=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.TRIM_VALUES)){
					trimValues=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.INHERITANCE_MAPPING)){
					inheritanceMapping=InheritanceMapping.valueOf((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.CREATEFEATURETYPE4ENUM)){
					createEnumTypes=CreateEnumFeatureTypes.valueOf((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.GEOMETRY_ENCODING)){
					geometryEncoding=GeometryEncoding.valueOf((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.GEOM_ATTR_MAPPING)){
					geomAttrMapping=GeomAttrMapping.valueOf((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.ILI1_ENUMASITFCODE)){
					ili1EnumAsItfCode=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.CHECK_UNIQUEOID)){
					checkUniqueOid=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.ILI1_RENUMBERTID)){
					ili1RenumberTid=FmeUtility.isTrue((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.HTTP_PROXYHOST)){
					httpProxyHost=StringUtility.purge((String)ele.get(1));
				}else if(val.equals(readerKeyword+"_"+Main.HTTP_PROXYPORT)){
					httpProxyPort=StringUtility.purge((String)ele.get(1));
				}
			}
		}

		if(httpProxyHost!=null){
			EhiLogger.logState("httpProxyHost <"+httpProxyHost+">");
			System.setProperty("http.proxyHost", httpProxyHost);
			if(httpProxyPort!=null){
				EhiLogger.logState("httpProxyPort <"+httpProxyPort+">");
				System.setProperty("http.proxyPort", httpProxyPort);
			}
		}else{
			System.setProperty("java.net.useSystemProxies", "true");
		}
		
		EhiLogger.logState("checkUniqueOid <"+checkUniqueOid+">");
		EhiLogger.logState("checkAttrType <"+checkAttrType+">");
		EhiLogger.logState("checkAttrMultiplicity <"+checkAttrMultiplicity+">");
		EhiLogger.logState("trimValues <"+trimValues+">");
		EhiLogger.logState("geometryEncoding <"+GeometryEncoding.toString(geometryEncoding)+">");
		EhiLogger.logState("geoAttrMapping <"+GeomAttrMapping.toString(geomAttrMapping)+">");
		EhiLogger.logState("ili1RenumberTid <"+ili1RenumberTid+">");
		EhiLogger.logState("createLineTables <"+createLineTableFeatures+">");
		EhiLogger.logState("skipPolygonBuilding <"+skipPolygonBuilding+">");
		EhiLogger.logState("inheritanceMapping <"+InheritanceMapping.toString(inheritanceMapping)+">");
		EhiLogger.logState("createEnumTypes <"+CreateEnumFeatureTypes.toString(createEnumTypes)+">");
		EhiLogger.logState("ili1AddDefVal <"+ili1AddDefVal+">");
		EhiLogger.logState("ili1EnumAsItfCode <"+ili1EnumAsItfCode+">");
		EhiLogger.logState("ili1ConvertArea <"+(ili1ConvertArea!=null?ili1ConvertArea:"")+">");
		EhiLogger.logState("ili1ConvertSurface <"+(ili1ConvertSurface!=null?ili1ConvertSurface:"")+">");
		EhiLogger.logState("ili1CheckConvert <"+ili1CheckPolygonBuilding+">");
		
		if(models==null){
			//models=mappingFile.fetchString(readerKeyword+"_"+Ili2fme.MODELS);
			models=Main.DATA_PLACEHOLDER;
		}
		EhiLogger.traceState("models <"+models+">");
		
		EhiLogger.traceState("topicsFilter <"+(topicsFilter!=null?topicsFilter:"")+">");
		if(topicsFilter!=null){
			String topicFilter[]=topicsFilter.split(";");
			for(int i=0;i<topicFilter.length;i++){
				topicFilter[i]=ch.ehi.basics.tools.StringUtility.purge(topicFilter[i]);
				if(topicFilter[i]!=null){
					if(topicFilterv==null){
						topicFilterv=new HashSet();
					}
					EhiLogger.logState("topicFilter <"+topicFilter[i]+">");
					topicFilterv.add(topicFilter[i]);
				}
			}
		}
		
		xtfFile=(String)args.get(0);
		if(xtfFile.length()>=2 && xtfFile.charAt(0)=='/' && xtfFile.charAt(1)=='/'){
			StringBuffer x=new StringBuffer(xtfFile);
			x.setCharAt(0,'\\');
			x.setCharAt(1,'\\');
			xtfFile=x.toString();
		}
		EhiLogger.logState("xtfFile <"+xtfFile+">");

		if(modeldir==null){
			//modeldir=mappingFile.fetchString(readerKeyword+"_"+Ili2fme.MODEL_DIR);
			modeldir=new java.io.File(session.fmeHome(),"plugins/interlis2/ili22models").getAbsolutePath();
			modeldir=new java.io.File(session.fmeHome(),"plugins/interlis2/ilimodels").getAbsolutePath()+";"+modeldir;
			modeldir="http://models.interlis.ch/;"+modeldir;
			modeldir=new java.io.File(xtfFile).getAbsoluteFile().getParent()+";"+modeldir;
		}else{
			int startPos=modeldir.indexOf(Main.XTFDIR_PLACEHOLDER);
			if(startPos>-1){
				StringBuffer buf=new StringBuffer(modeldir);
				buf.replace(startPos,startPos+Main.XTFDIR_PLACEHOLDER.length(),new java.io.File(xtfFile).getAbsoluteFile().getParent());
				modeldir=buf.toString();
			}
		}
		EhiLogger.logState("modeldir <"+modeldir+">");
		
		String xtfExt=ch.ehi.basics.view.GenericFileFilter.getFileExtension(xtfFile);
		if(xtfExt!=null){
			xtfExt=xtfExt.toLowerCase();
		}
		if(xtfExt!=null && xtfExt.equals("itf")){
			if(ili1ConvertArea==null && ili1ConvertSurface==null && skipPolygonBuilding==false && createLineTableFeatures==false){
				EhiLogger.logState("use builtin polygon building");
				formatMode=MODE_ITF2;
			}else{
				EhiLogger.logState("use raw ITF reader");
				formatMode=MODE_ITF;
			}
		}else if(xtfExt!=null && xtfExt.equals("gml")){
			throw new IllegalArgumentException("INTERLIS GML not yet supported by ili2fme reader");
		}else{
			formatMode=MODE_XTF;
		}
		if(xtfExt!=null && xtfExt.equals("ili")){
			ArrayList iliFilev=new ArrayList();
			iliFilev.add(xtfFile);
			xtfFile=null;
			// compile models
			{
				// create repository manager
				ch.interlis.ilirepository.IliManager manager=new ch.interlis.ilirepository.IliManager();
				// set list of repositories to search
				manager.setRepositories(modeldir.split(";"));
				// get complete list of required ili-files
				ch.interlis.ili2c.config.Configuration config=manager.getConfigWithFiles(iliFilev);
				config.setGenerateWarnings(false);
				ch.interlis.ili2c.Ili2c.logIliFiles(config);
				// compile models
				iliTd=ch.interlis.ili2c.Ili2c.runCompiler(config);
				if(iliTd==null){
					// compiler failed
					throw new IllegalArgumentException("INTERLIS compiler failed");
				}
			}
			// INTERLIS 1?
			if(iliTd.getIli1Format()!=null){
				formatMode=MODE_ITF;
			}else{
				formatMode=MODE_XTF;
			}
		}else if(models.equals(Main.DATA_PLACEHOLDER) || models.equals(Main.DEPRECATED_XTF_PLACEHOLDER)){
			// get model names out of transfer file
			iliModelv=new ArrayList<String>();
			StartBasketEvent be=null;
			XtfStartTransferEvent xtfStart=null;
			try{
				inputFile=openInputFile(xtfFile);
				if(formatMode==MODE_XTF){
					ioxReader=new ch.interlis.iom_j.xtf.XtfReader(inputFile);
				}else if(formatMode==MODE_ITF || formatMode==MODE_ITF2){
					//  just try to find out modelname; no need to build polygons
					ioxReader=new ch.interlis.iom_j.itf.ItfReader(inputFile);
				}else{
					throw new IllegalStateException("unexpected formatMode");
				}

				// get first basket
				IoxEvent event;
				do{
					event=ioxReader.read();
					if(event instanceof StartBasketEvent){
						be=(StartBasketEvent)event;
						break;
					}else if(event instanceof XtfStartTransferEvent){
						xtfStart=(XtfStartTransferEvent)event;
					}
				}while(!(event instanceof EndTransferEvent));
				
			}finally{
				if(ioxReader!=null){
					ioxReader.close();
					ioxReader=null;
				}
				if(inputFile!=null){
					inputFile.close();
					inputFile=null;
				}
				
			}
			
			// no baskets?
			if(be==null){
				// no models!
				iliTd=null;
				throw new IllegalArgumentException("no baskets in xtf-file");
			}
			String model[]=be.getType().split("\\.");
			iliModelv.add(model[0]);
			if(xtfStart!=null){
				XtfUtility.addModels(iliModelv,xtfStart);
			}
			for(String modelName:iliModelv){
				EhiLogger.logState("model from xtf <"+modelName+">");
			}
			// compile models
			{
				// create repository manager
				ch.interlis.ilirepository.IliManager manager=new ch.interlis.ilirepository.IliManager();
				// set list of repositories to search
				manager.setRepositories(modeldir.split(";"));
				// get complete list of required ili-files
				ch.interlis.ili2c.config.Configuration config=manager.getConfig(iliModelv,0.0);
				ch.interlis.ili2c.Ili2c.logIliFiles(config);
				config.setGenerateWarnings(false);
				// compile models
				iliTd=ch.interlis.ili2c.Ili2c.runCompiler(config);
				if(iliTd==null){
					// compiler failed
					throw new IllegalArgumentException("INTERLIS compiler failed");
				}
			}
		}else{
			// parse string
			iliModelv=new ArrayList<String>(java.util.Arrays.asList(models.split(";")));
			// compile models
			{
				// create repository manager
				ch.interlis.ilirepository.IliManager manager=new ch.interlis.ilirepository.IliManager();
				// set list of repositories to search
				manager.setRepositories(modeldir.split(";"));
				// get complete list of required ili-files
				ch.interlis.ili2c.config.Configuration config=manager.getConfig(iliModelv,0.0);
				ch.interlis.ili2c.Ili2c.logIliFiles(config);
				config.setGenerateWarnings(false);
				// compile models
				iliTd=ch.interlis.ili2c.Ili2c.runCompiler(config);
				if(iliTd==null){
					// compiler failed
					return;
				}
			}
		}
		
		if(formatMode==MODE_XTF){
			transferViewables=ModelUtility.getXtfTransferViewables(iliTd,inheritanceMapping);
		}else if(formatMode==MODE_ITF){
			transferViewables=ModelUtility.getItfTransferViewables(iliTd);
		}else if(formatMode==MODE_ITF2){
			transferViewables=ModelUtility.getItf2TransferViewables(iliTd);
		}else{
			throw new IllegalStateException("unexpected formatMode");
		}
		transferViewablei=null;
		
		if(createEnumTypes==CreateEnumFeatureTypes.ONETYPEPERENUMDEF || createEnumTypes==CreateEnumFeatureTypes.SINGLETYPE){
			collectEnums(iliTd);
		}
		
		if(geometryEncoding!=GeometryEncoding.OGC_HEXBIN){
			geomConv=new GeometryConverter(session,geometryEncoding);
		}

		// setup mapping from xml-elementnames to types
		if(formatMode==MODE_XTF){
			tag2class=ch.interlis.ili2c.generator.XSDGenerator.getTagMap(iliTd);
		}else if(formatMode==MODE_ITF){
			tag2class=ch.interlis.iom_j.itf.ModelUtilities.getTagMap(iliTd);
		}else if(formatMode==MODE_ITF2){
			tag2class=ch.interlis.iom_j.itf.ModelUtilities.getTagMap2(iliTd);
		}else{
			throw new IllegalStateException("unexpected formatMode");
		}
		
		// get longest qualified ili name
		Iterator iliqnamei=tag2class.keySet().iterator();
		while(iliqnamei.hasNext()){
			String iliqname=(String)iliqnamei.next();
			if(iliqname.length()>iliQNameSize){
				iliQNameSize=iliqname.length();
			}
		}
		iliQNameSize+=1;
		
		if(checkUniqueOid){
			checkoids=new HashMap();
		}
		// ASSERT: ready to scan schema or read data
	}

	// Terminate the reader mid-stream.  Any special actions to shut
	// down a reader not finished reading data should be taken in this
	// method.  For most readers, nothing will be done.
	public void abort() throws Exception {
		try{
			myabort();
		}catch(Exception ex){
			EhiLogger.logError(ex);
			throw ex;
		}
	}
	private void myabort() throws Exception {
		cleanup();
	}

	// Close the reader after it has exhausted its data source.
	public void close() throws Exception {
		try{
			myclose();
		}catch(Exception ex){
			EhiLogger.logError(ex);
			throw ex;
		}
	}
	private void myclose() throws Exception {
		cleanup();
	}
	
	// Return the unique ID assigned to this reader.
	// All Java reader return 0.
	public int id() {
		return 0;
	}

	public IFMEFeature read(IFMEFeature ret) 
		throws Exception 
	{
		try{
			return myread(ret);
		}catch(Error ex){
			EhiLogger.logError(ex);
			throw new Exception(ex);
		}
	}
	HashMap checkoids=null; // new HashMap();
	private GeomAttrIterator geomAttrIterator=null;
	private IFMEFeature myread(IFMEFeature ret) 
		throws Exception 
	{
		// ili-file mode?
		if(xtfFile==null){
			if(createEnumTypes==CreateEnumFeatureTypes.ONETYPEPERENUMDEF || createEnumTypes==CreateEnumFeatureTypes.SINGLETYPE){
				return processEnums(ret);
			}
			// no features, just schema features!
			return null;
		}
		if(createEnumTypes==CreateEnumFeatureTypes.ONETYPEPERENUMDEF || createEnumTypes==CreateEnumFeatureTypes.SINGLETYPE){
			IFMEFeature enumEle=processEnums(ret);
			if(enumEle!=null){
				return enumEle;
			}
		}
		if(doPipeline){
			if(dataerrs!=null && dataerrs.size()>0){
				IoxInvalidDataException dataerr=dataerrs.get(0);
				mapDataErr(ret, dataerr);
				dataerrs.remove(0);
				return ret;
			}
			Iterator surfaceBuilderi=surfaceBuilders.keySet().iterator();
			while(surfaceBuilderi.hasNext()){
				//EhiLogger.debug("check pipeline for output");
				AttributeDef attr=(AttributeDef)surfaceBuilderi.next();
				IFMEFactoryPipeline surfaceBuilder=(IFMEFactoryPipeline)surfaceBuilders.get(attr);
				boolean eop=surfaceBuilder.getOutputFeature(ret);					
				if(eop){
					//EhiLogger.debug("return feature from pipeline");
					checkConvertedFeature(ret,attr);
					return ret;
				}
				//EhiLogger.debug("end of pipeline; remove it");
				surfaceBuilder.dispose();
				surfaceBuilderi.remove();
			}
		}
		if(geomAttrIterator!=null){
			if(geomAttrIterator.hasNext()){
				geomAttrIterator.next(ret);
				return ret;
			}
			geomAttrIterator.dispose();
			geomAttrIterator=null;
		}
		// if first call?
		if(ioxReader==null){
			inputFile=openInputFile(xtfFile);
			
			if(formatMode==MODE_XTF){
				ioxReader=new ch.interlis.iom_j.xtf.XtfReader(inputFile);
			}else if(formatMode==MODE_ITF){
				ioxReader=new ch.interlis.iom_j.itf.ItfReader(inputFile);
				((ItfReader)ioxReader).setModel(iliTd);		
				((ItfReader)ioxReader).setReadEnumValAsItfCode(ili1EnumAsItfCode);		
				((ItfReader)ioxReader).setRenumberTids(ili1RenumberTid);
				if(ili1AddDefVal){
					ioxReader=new ch.ehi.iox.adddefval.ItfAddDefValueReader(ioxReader,iliTd,ili1EnumAsItfCode);
				}
			}else if(formatMode==MODE_ITF2){
				ioxReader=new ch.interlis.iom_j.itf.ItfReader2(inputFile,!ili1CheckPolygonBuilding);
				((ItfReader2)ioxReader).setModel(iliTd);		
				((ItfReader2)ioxReader).setReadEnumValAsItfCode(ili1EnumAsItfCode);		
				((ItfReader2)ioxReader).setRenumberTids(ili1RenumberTid);
				if(ili1AddDefVal){
					ioxReader=new ch.ehi.iox.adddefval.ItfAddDefValueReader(ioxReader,iliTd,ili1EnumAsItfCode);
				}
			}else{
				throw new IllegalStateException("unexpected formatMode");
			}
		}
		IoxEvent event;
		while(true){
			event=ioxReader.read();
			//EhiLogger.debug("event "+event.getClass().getName());
			if(event instanceof StartTransferEvent){
				ret.setFeatureType(Main.XTF_TRANSFER);
				if(event instanceof XtfStartTransferEvent){
					XtfStartTransferEvent startEvent=(XtfStartTransferEvent)event;
					String value=null;
					List<ch.interlis.iom_j.xtf.OidSpace> oids=startEvent.getOidSpaces();
					//EhiLogger.debug("oids.size() "+oids.size());
					for(int i=0;i<oids.size();i++){
						ch.interlis.iom_j.xtf.OidSpace oid=(ch.interlis.iom_j.xtf.OidSpace)oids.get(i);
						String prefix=Main.XTF_OIDSPACE+"{"+i+"}.";
						value=oid.getName();
						if(value!=null){
							ret.setStringAttribute(prefix+Main.XTF_OIDNAME,value);
						}
						value=oid.getOiddomain();
						if(value!=null){
							ret.setStringAttribute(prefix+Main.XTF_OIDDOMAIN,value);
						}
					}
					value=startEvent.getComment();
					if(value!=null){
						ret.setStringAttribute(Main.XTF_COMMENT, value);
					}
				}
				return ret;
			}else if(!skipBasket && (event instanceof ObjectEvent)){
				ObjectEvent oe=(ObjectEvent)event;
				IomObject iomObj=oe.getIomObject();
				if(checkoids!=null){
					String oid=iomObj.getobjectoid();
					if(checkoids.containsKey(oid)){
						EhiLogger.logError(iomObj.getobjecttag()+" at line "+iomObj.getobjectline()+": duplicate oid "+oid+" (same as at line "+((Integer)checkoids.get(oid)).toString()+")");
					}else{
						checkoids.put(oid, new Integer(iomObj.getobjectline()));
					}
				}
				//EhiLogger.debug("iomObj "+iomObj.toString());
				// translate object
				try{
					//EhiLogger.debug("object "+iomObj.getobjecttag()+" "+iomObj.getobjectoid());
					ArrayList<String> geomAttrsCollector=new ArrayList<String>();
					ret=mapFeature(ret,iomObj,null,geomAttrsCollector);
					// feature feed into pipeline
					if(ret==null){
						// create a new one
						ret=session.createFeature();
						// read next object from transferfile
						continue;
					}
					if(geomAttrMapping==GeomAttrMapping.REPEAT_FEATRUE){
						ViewableWrapper wrapper=((ViewableWrapper)transferViewables.get(iomObj.getobjecttag()));
						if(wrapper.getViewable()!=null){
							geomAttrIterator=new GeomAttrIterator(session,ret,geomAttrsCollector,geometryEncoding);
						}
					}
				}catch(DataException ex){
					EhiLogger.logError("object "+iomObj.getobjecttag()+" "+iomObj.getobjectoid()+" skipped; "+ch.interlis.iom_j.Iom_jObject.dumpObject(iomObj),ex);
					ret.dispose();
					ret=session.createFeature();
					continue;
					//return null;
				}catch(java.lang.Exception ex){
					EhiLogger.logError("object "+iomObj.getobjecttag()+" "+iomObj.getobjectoid()+" "+ch.interlis.iom_j.Iom_jObject.dumpObject(iomObj),ex);
					return null;
				}
				return ret;
			}else if(event instanceof StartBasketEvent){
				StartBasketEvent be=(StartBasketEvent)event;
				currentBid=be.getBid();
				doPipeline=false;
				surfaceBuilders=new HashMap();
	        	dataerrs = null;
				// map basket (to a feature! to get metainfo about basket)
				String topic=be.getType();
				EhiLogger.logState(topic+" "+currentBid+"...");
				if(topicFilterv!=null && !topicFilterv.contains(topic)){
					skipBasket=true;
					continue;
				}
				mapBasket(ret,be);
				return ret;
			}else if(event instanceof EndBasketEvent){
				currentBid=null;
				if(skipBasket){
					skipBasket=false;
					continue;
				}
				if(dataerrs==null && ioxReader instanceof ItfReader2){
		        	dataerrs = new ArrayList<IoxInvalidDataException>(((ItfReader2) ioxReader).getDataErrs());
		        	if(dataerrs.size()>0){
		        		for(IoxInvalidDataException dataerr:dataerrs){
		        			if(ili1CheckPolygonBuilding){
			        			EhiLogger.logError(dataerr);
		        			}else{
			        			EhiLogger.logState(dataerr.getMessage());
		        			}
		        		}
		        		((ItfReader2) ioxReader).clearDataErrs();
		        	}
				}
				if(dataerrs!=null && dataerrs.size()>0){
					IoxInvalidDataException dataerr=dataerrs.get(0);
					mapDataErr(ret, dataerr);
					dataerrs.remove(0);
					doPipeline=true;
					return ret;
				}
				// flush pipelines
				Iterator surfaceBuilderi=surfaceBuilders.keySet().iterator();
				while(surfaceBuilderi.hasNext()){
					AttributeDef attr=(AttributeDef)surfaceBuilderi.next();
					IFMEFactoryPipeline surfaceBuilder=(IFMEFactoryPipeline)surfaceBuilders.get(attr);
					try{
						EhiLogger.traceState("flush polygon building pipeline "+attr.getScopedName(null));
						surfaceBuilder.allDone();
					}catch(Exception ex){
						String attrQName=attr.getContainer().getScopedName(null)+"."+attr.getName();
						EhiLogger.logError("failed to build polygons for "+attrQName,ex);
						// remove pipeline
						surfaceBuilderi.remove();
					}
				}
				// check pipelines for output
				surfaceBuilderi=surfaceBuilders.keySet().iterator();
				while(surfaceBuilderi.hasNext()){
					//EhiLogger.debug("check pipeline for output");
					AttributeDef attr=(AttributeDef)surfaceBuilderi.next();
					IFMEFactoryPipeline surfaceBuilder=(IFMEFactoryPipeline)surfaceBuilders.get(attr);
					boolean eop=surfaceBuilder.getOutputFeature(ret);					
					// is not end of pipeline?
					if(eop){
						//EhiLogger.debug("return feature from pipeline");
						if(ili1CheckPolygonBuilding){
							checkConvertedFeature(ret,attr);
						}
						doPipeline=true;
						return ret;
					}
					//EhiLogger.debug("end of pipeline; remove it");
					surfaceBuilderi.remove();
				}
			}else if(event instanceof EndTransferEvent){
				// end of file reached
				ioxReader.close();
				ioxReader=null;
				if(listener.hasSeenErrors()){
					listener.clearErrors();
					throw new Exception("INTERLIS 2 reader failed");
				}
				return null;
			}else{
				// ignore other events
			}
		}
	}
	private void mapDataErr(IFMEFeature ret, IoxInvalidDataException dataerr) throws DataException {
		ret.setFeatureType(Main.XTF_ERRORS);
		ret.setStringAttribute(Main.XTF_ERRORS_MESSAGE, dataerr.getMessage());
		if(dataerr.getIliqname()!=null){
			ret.setStringAttribute(Main.XTF_ERRORS_ILINAME, dataerr.getIliqname());
		}
		IomObject geom=dataerr.getGeom();
		if(geom!=null){
			 IFMEGeometry fmeGeom=null;
			 try{
				 fmeGeom=Iox2fme.geom2FME(session,geom);
				 ret.setGeometry(fmeGeom);
			 }finally{
				 if(fmeGeom!=null){
					 fmeGeom.dispose();
					 fmeGeom=null;
				 }
			 }
		}
		if(dataerr.getTids()!=null){
			int idx=0;
			for(String tid:dataerr.getTids()){
				if(tid!=null){
					ret.setStringAttribute(Main.XTF_ERRORS_TID+"{"+ Integer.toString(idx++) +"}", tid);
				}
			}
		}
	}

	private java.io.InputStream openInputFile(String xtfFile)
			throws IOException, URISyntaxException {
		java.io.InputStream input = null;
		String urilc = xtfFile.toLowerCase();
		if (!urilc.startsWith("http:") && !urilc.startsWith("https:")) {
			input = new java.io.FileInputStream(xtfFile);
		} else {
			// fetch from http server (handle redirects)
			java.net.URL url = null;
			url = new java.net.URI(xtfFile).toURL();
			EhiLogger.traceState("fetching <" + url + "> ...");
			java.net.URLConnection conn = null;
			//
			// java -Dhttp.proxyHost=myproxyserver.com -Dhttp.proxyPort=80
			// MyJavaApp
			//
			// System.setProperty("http.proxyHost", "myProxyServer.com");
			// System.setProperty("http.proxyPort", "80");
			//
			// System.setProperty("java.net.useSystemProxies", "true");
			//
			// since 1.5
			// Proxy instance, proxy ip = 123.0.0.1 with port 8080
			// Proxy proxy = new Proxy(Proxy.Type.HTTP, new
			// InetSocketAddress("123.0.0.1", 8080));
			// URL url = new URL("http://www.yahoo.com");
			// HttpURLConnection uc =
			// (HttpURLConnection)url.openConnection(proxy);
			// uc.connect();
			//
			conn = url.openConnection();
			input = new java.io.BufferedInputStream(conn.getInputStream());
		}
		return input;
	}

	private void checkConvertedFeature(IFMEFeature ret,AttributeDef attr){
		String featureType=ret.getFeatureType();
		if(featureType.startsWith(ERR_FEATURETYPE_PREFIX)){
			String errmsg=featureType;
			if(ret.attributeExists(ERRMSG_ATTRIBUTE)){
					try {
						String msg=ret.getStringAttribute(ERRMSG_ATTRIBUTE);
						if(msg!=null && msg.length()>0){
							errmsg=msg;
						}
					} catch (FMEException ex) {
						EhiLogger.logError(ex);
					}
			}
			if(ili1CheckPolygonBuilding){
				EhiLogger.logError("polygon building error: "+attr.toString()+", "+errmsg);
			}else{
				EhiLogger.logState("polygon building error: "+attr.toString()+", "+errmsg);
			}
			String geomAttrIliQName=attr.getContainer().getScopedName(null)+"."+attr.getName();
			ret.setStringAttribute(Main.XTF_ERRORS_ILINAME, geomAttrIliQName);
			ret.setStringAttribute(Main.XTF_ERRORS_MESSAGE, "polygon building error: "+errmsg);
			ret.setFeatureType(Main.XTF_ERRORS);
		}
	}
	private void mapBasket(IFMEFeature ret,StartBasketEvent be)
	{
		ret.setFeatureType(Main.XTF_BASKETS);
		ret.setStringAttribute(Main.XTF_TOPIC,be.getType());
		ret.setStringAttribute(Main.XTF_ID,be.getBid());
		String startState=be.getStartstate();
		if(startState!=null){
			ret.setStringAttribute(Main.XTF_STARTSTATE,startState);
		}
		String endState=be.getEndstate();
		if(endState!=null){
			ret.setStringAttribute(Main.XTF_ENDSTATE,endState);
		}
		String consistency=FmeUtility.mapIox2FmeConsistency(be.getConsistency());
		if(consistency!=null){
			ret.setStringAttribute(Main.XTF_CONSISTENCY,consistency);
		}
	}
	private IFMEFeature mapFeature(IFMEFeature ret,IomObject iomObj,String prefix,ArrayList<String> geomAttrsCollector)
	throws DataException,ConfigException
	{
		boolean isStruct=prefix!=null;
		if(!isStruct)prefix="";
		String tag=iomObj.getobjecttag();
		if(formatMode==MODE_XTF && tag.equals(Main.DELETE_TAG)){
			ret.setFeatureType(Main.XTF_DELETEOBJECT);
			ret.setStringAttribute(Main.XTF_ID,iomObj.getobjectoid());
			ret.setStringAttribute(Main.XTF_BASKET,currentBid);
			return ret;
		}
		ViewableWrapper wrapper=null;
		if(formatMode==MODE_XTF || formatMode==MODE_ITF2){
			Viewable aclass=(Viewable)tag2class.get(tag);
			if(aclass==null){
				EhiLogger.logError("line "+iomObj.getobjectline()+": unknonw class <"+tag+">; ignored");
				return null;
			}
			wrapper=((ViewableWrapper)transferViewables.get(tag));
		}else if(formatMode==MODE_ITF){
			wrapper=((ViewableWrapper)transferViewables.get(tag));
			if(wrapper==null){
				EhiLogger.logError("line "+iomObj.getobjectline()+": unknonw class <"+tag+">; ignored");
				return null;
			}
		}
		//EhiLogger.debug("aclass <"+aclass+">, root <"+rootClass+">");
		if(!isStruct){
			ret.setFeatureType(wrapper.getFmeFeatureType());
			if(iomObj.getobjectoid()!=null){ // an ili2 assocObj might have no oid
				ret.setStringAttribute(Main.XTF_ID,iomObj.getobjectoid());
			}
			ret.setStringAttribute(Main.XTF_BASKET,currentBid);
			if(formatMode==MODE_XTF){
				String consistency=FmeUtility.mapIox2FmeConsistency(iomObj.getobjectconsistency());
				if(consistency!=null){
					ret.setStringAttribute(Main.XTF_CONSISTENCY,consistency);
				}
				String operation=FmeUtility.mapIox2FmeOperation(iomObj.getobjectoperation());
				if(operation!=null){
					ret.setStringAttribute(Main.XTF_OPERATION,operation);
				}
			}
		}
		if(formatMode==MODE_ITF){
			// if SURFACE helper table
			if(wrapper.isHelper() && wrapper.getGeomAttr4FME().getDomainResolvingAliases() instanceof SurfaceType){
				//add ref to main table
				String fkName=ch.interlis.iom_j.itf.ModelUtilities.getHelperTableMainTableRef(wrapper.getGeomAttr4FME());
				IomObject structvalue=iomObj.getattrobj(fkName,0);
				String refoid=structvalue.getobjectrefoid();
				ret.setStringAttribute(fkName,refoid);
			}
		}
		AttributeDef geomattr=isStruct ? null : wrapper.getGeomAttr4FME();
		if(geomattr!=null){
			ret.setStringAttribute(Main.XTF_GEOMATTR,geomattr.getName());
		}
		 ret.setStringAttribute(prefix+Main.XTF_CLASS,tag);
		Iterator iter;
		if(formatMode==MODE_ITF){
			iter = wrapper.getAttrIterator();
		}else{
			Viewable aclass=(Viewable)tag2class.get(tag);
			iter = aclass.getAttributesAndRoles2();
		}

		boolean feedToPipeline=false;
		StringBuffer cloneFeatureType=new StringBuffer();
		while (iter.hasNext()) {
			ViewableTransferElement prop = (ViewableTransferElement)iter.next();
			if (prop.obj instanceof AttributeDef) {
				AttributeDef attr = (AttributeDef) prop.obj;
				feedToPipeline=mapAttributeValue(feedToPipeline,geomattr,ret,iomObj,attr,prefix,wrapper,cloneFeatureType,geomAttrsCollector);
			}
			if(prop.obj instanceof RoleDef){
				RoleDef role = (RoleDef) prop.obj;
				String roleName=role.getName();
				// a role of an embedded association?
				if(prop.embedded){
					AssociationDef roleOwner = (AssociationDef) role.getContainer();
					if(roleOwner.getDerivedFrom()==null){
						// not just a link?
						 IomObject structvalue=iomObj.getattrobj(roleName,0);
						 if(structvalue!=null){
							if (roleOwner.getAttributes().hasNext()
								|| roleOwner.getLightweightAssociations().iterator().hasNext()) {
								 // add association attributes
								mapFeature(ret,structvalue,prefix+roleName+"{0}.",geomAttrsCollector);
							}
							 String refoid=structvalue.getobjectrefoid();
							 long orderPos=structvalue.getobjectreforderpos();
							 if(orderPos!=0){
								// refoid,orderPos
								ret.setStringAttribute(prefix+roleName, refoid);
								ret.setStringAttribute(prefix+roleName+"."+Main.ORDERPOS, Long.toString(orderPos));
							 }else{
								// refoid
								ret.setStringAttribute(prefix+roleName, refoid);
							 }
						 }
					}
				}else{
					if(!((AssociationDef)role.getContainer()).isLightweight()){
						 IomObject structvalue=iomObj.getattrobj(roleName,0);
						 String refoid=structvalue.getobjectrefoid();
						 long orderPos=structvalue.getobjectreforderpos();
						 if(orderPos!=0){
							// refoid,orderPos
							ret.setStringAttribute(prefix+roleName, refoid);
							ret.setStringAttribute(prefix+roleName+"."+Main.ORDERPOS, Long.toString(orderPos));
						 }else{
							// refoid
							ret.setStringAttribute(prefix+roleName, refoid);
						 }
					}
				}
			}
		}
		if(feedToPipeline){
			IFMEFeature clone=null;
			if(cloneFeatureType.length()!=0){
				try{
					clone=session.createFeature();
					ret.clone(clone);
					clone.setFeatureType(cloneFeatureType.toString());
				}catch(FMEException ex){
					EhiLogger.logError("failed to clone surface/area feature",ex);
				}
			}
			if(skipPolygonBuilding){
				ret.dispose();
				ret=null;
			}else{
				IFMEFactoryPipeline surfaceBuilder=null;
				surfaceBuilder=getSurfaceBuilder(wrapper.getGeomAttr4FME());
				try{
					//EhiLogger.debug("feed feature "+iomObj.getobjectoid()+" "+ret.getFeatureType()+" to pipeline");
					surfaceBuilder.processFeature(ret);
					//EhiLogger.debug("done "+iomObj.getobjectoid()+" "+ret.getFeatureType()+" to pipeline");
				}catch(FMEException ex){
					EhiLogger.logError("failed to feed feature to surface builder pipeline",ex);
				}
				ret.dispose();
				ret=null;
			}
			return clone;
		}
		return ret;
	}
	private boolean mapAttributeValue(boolean feedToPipeline,AttributeDef geomattr, IFMEFeature ret, IomObject iomObj,AttributeDef attr, String prefix,ViewableWrapper wrapper,StringBuffer cloneFeatureType,ArrayList<String> geomAttrsCollector) 
	throws DataException,ConfigException
	{
		if(prefix==null){
			prefix="";
		}
		Type type = attr.getDomainResolvingAliases();
		String attrName=attr.getName();
		if (type instanceof CompositionType){
		 int valuec=iomObj.getattrvaluecount(attrName);
		 for(int valuei=0;valuei<valuec;valuei++){
			IomObject value=iomObj.getattrobj(attrName,valuei);
			if(value!=null){
				mapFeature(ret,value,prefix+attr.getName()+"{"+Integer.toString(valuei)+"}.",geomAttrsCollector);
			}
		 }
		}else if (type instanceof PolylineType){
			 IomObject value=iomObj.getattrobj(attrName,0);
			 if(value!=null){
				 if(attr!=geomattr){
					 if(geomConv==null){
						 String wkb;
							try {
								wkb = Iox2jts.polyline2hexwkb(value,getP((PolylineType)type));
							} catch (Iox2jtsException e) {
								throw new DataException(e);
							}
							checkWkb(ret,prefix+attrName,wkb);
							 //EhiLogger.debug(attrName+" "+wkb);
							 ret.setStringAttribute(prefix+attrName, wkb);
					 }else{
						 geomConv.polyline2FME(ret,prefix+attrName,value);
					 }
					 geomAttrsCollector.add(prefix+attrName);
				 }else{
					 ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_polyline");
					 if(doRichGeometry){
						 IFMEPath fmeLine=null;
						 try{
							 fmeLine=Iox2fme.polyline2FME(session,value,false);
							 ret.setGeometry(fmeLine);
						 }finally{
							 if(fmeLine!=null){
								 fmeLine.dispose();
								 fmeLine=null;
							 }
						 }
					 }else{
						 setPolyline(ret,value,false,getP((PolylineType)type));
					 }
				 }
			 }					
		 }else if(type instanceof SurfaceType){
			if(attr!=geomattr){
				 IomObject value=iomObj.getattrobj(attrName,0);
				 if(value!=null){
					 if(geomConv==null){
						 String wkb;
							try {
								wkb = Iox2jts.surface2hexwkb(value,getP((SurfaceType)type));
							} catch (Iox2jtsException e) {
								throw new DataException(e);
							}
		 					checkWkb(ret,prefix+attrName,wkb);
							 //EhiLogger.debug(attrName+" "+wkb);
							 ret.setStringAttribute(prefix+attrName, wkb);
						 
					 }else{
						 geomConv.surface2FME(ret,prefix+attrName,value);
					 }
					 geomAttrsCollector.add(prefix+attrName);
				 }
			 }else{
				 // is itf? 
				 if(formatMode==MODE_ITF){
					 // main table?
					 if(!wrapper.isHelper()){
						 	// no geometry
						 	feedToPipeline=true;
						 	if(createLineTableFeatures){
						 		cloneFeatureType.append(ret.getFeatureType()+"_MT");
						 	}
					 }else{
						// helper table
						 IomObject value=iomObj.getattrobj(ch.interlis.iom_j.itf.ModelUtilities.getHelperTableGeomAttrName(attr),0);
						 if(value!=null){
								// helper table
								ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_polyline");
								 if(doRichGeometry){
									 IFMEPath fmeLine=null;
									 try{
										 fmeLine=Iox2fme.polyline2FME(session,value,false);
										 ret.setGeometry(fmeLine);
									 }finally{
										 if(fmeLine!=null){
											 fmeLine.dispose();
											 fmeLine=null;
										 }
									 }
								 }else{
									setPolyline(ret,value,false,getP((SurfaceType)type));
								 }
								feedToPipeline=true;
								if(createLineTableFeatures){
									cloneFeatureType.append(ret.getFeatureType()+"_LT");
								}
								// add line attributes
								SurfaceType surfaceType=(SurfaceType)type;
								Table lineAttrTable=surfaceType.getLineAttributeStructure();
								if(lineAttrTable!=null){
								    Iterator attri = lineAttrTable.getAttributes ();
								    while(attri.hasNext()){
								    	AttributeDef lineattr=(AttributeDef)attri.next();
										mapAttributeValue(false,null, ret, iomObj,lineattr, null,null,null,geomAttrsCollector);
								    }
								}
						 }
					 }
				 }else{
					 // XTF
					 //ret.setStringAttribute("fme_geometry{0}", "xtf_surface");
					 IomObject value=iomObj.getattrobj(attrName,0);
					 if(value!=null){
							ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_surface");
							 if(doRichGeometry){
								 IFMEArea fmeSurface=null;
								 try{
									 fmeSurface=Iox2fme.surface2FME(session,value);
									 ret.setGeometry(fmeSurface);
								 }finally{
									 if(fmeSurface!=null){
										 fmeSurface.dispose();
										 fmeSurface=null;
									 }
								 }
							 }else{
								setSurface(ret,value,(SurfaceType)type);
							 }
					 }
				 }
			 }
		 }else if(type instanceof AreaType){
			if(attr!=geomattr){
				 IomObject value=iomObj.getattrobj(attrName,0);
				 if(value!=null){
					 if(geomConv==null){
						 String wkb;
							try {
								wkb = Iox2jts.surface2hexwkb(value,getP((AreaType)type));
							} catch (Iox2jtsException e) {
								throw new DataException(e);
							}
		 					checkWkb(ret,prefix+attrName,wkb);
							 //EhiLogger.debug(attrName+" "+wkb);
							 ret.setStringAttribute(prefix+attrName, wkb);
					 }else{
						 geomConv.surface2FME(ret,prefix+attrName,value);
					 }
					 geomAttrsCollector.add(prefix+attrName);
				 }
			 }else{
				 //ret.setStringAttribute("fme_geometry{0}", "xtf_area");
					if(formatMode==MODE_ITF){
						feedToPipeline=true;
						// is helper table?
						if(wrapper.isHelper()){
							 IomObject value=iomObj.getattrobj(ch.interlis.iom_j.itf.ModelUtilities.getHelperTableGeomAttrName(attr),0);
							 if(value!=null){
									ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_polyline");
									 if(doRichGeometry){
										 IFMEPath fmeLine=null;
										 try{
											 fmeLine=Iox2fme.polyline2FME(session,value,false);
											 ret.setGeometry(fmeLine);
										 }finally{
											 if(fmeLine!=null){
												 fmeLine.dispose();
												 fmeLine=null;
											 }
										 }
									 }else{
											setPolyline(ret,value,false,getP((AreaType)type));
									 }
										if(createLineTableFeatures){
											cloneFeatureType.append(ret.getFeatureType()+"_LT");
										}
										// add line attributes
										AreaType surfaceType=(AreaType)type;
										Table lineAttrTable=surfaceType.getLineAttributeStructure();
										if(lineAttrTable!=null){
										    Iterator attri = lineAttrTable.getAttributes ();
										    while(attri.hasNext()){
										    	AttributeDef lineattr=(AttributeDef)attri.next();
												mapAttributeValue(false,null, ret, iomObj,lineattr, null,null,null,geomAttrsCollector);
										    }
										}
							 }
						}else{
							// main table
							 IomObject value=iomObj.getattrobj(attrName,0);
							 if(value!=null){
									ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_coord");
									 if(doRichGeometry){
										 IFMEPoint point=null;
										 try{
											 point=Iox2fme.coord2FME(session,value);
											 ret.setGeometry(point);
										 }finally{
											 if(point!=null){
												 point.dispose();
												 point=null;
											 }
										 }
									 }else{
											ret.setGeometryType(IFMEFeature.FME_GEOM_POINT);
											addCoord(ret,value);
									 }
									if(createLineTableFeatures){
										cloneFeatureType.append(ret.getFeatureType()+"_MT");
									}
							 }
						}
					}else{
						// XTF
						 IomObject value=iomObj.getattrobj(attrName,0);
						 if(value!=null){
								ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_area");
								 if(doRichGeometry){
									 IFMEArea fmeSurface=null;
									 try{
										 fmeSurface=Iox2fme.surface2FME(session,value);
										 ret.setGeometry(fmeSurface);
									 }finally{
										 if(fmeSurface!=null){
											 fmeSurface.dispose();
											 fmeSurface=null;
										 }
									 }
								 }else{
									setSurface(ret,value,(AreaType)type);
								 }
						 }
					}
			 }
		 }else if(type instanceof CoordType){
			 //ret.setStringAttribute("fme_geometry{0}", "xtf_coord");
			 IomObject value=iomObj.getattrobj(attrName,0);
			 if(value!=null){
				if(!value.getobjecttag().equals("COORD")){
					throw new DataException("COORD expected for attribute "+attrName);
				}
				if(attr!=geomattr){
					if(((CoordType)type).getDimensions().length==1){
						String c1=value.getattrvalue("C1");
						 if(c1!=null){
							 ret.setStringAttribute(prefix+attrName, c1);
						 }
					}else{
						if(geomConv==null){
							 String wkb;
								try {
									wkb = Iox2jts.coord2hexwkb(value);
								} catch (Iox2jtsException e) {
									throw new DataException(e);
								}
			 					checkWkb(ret,prefix+attrName,wkb);
								 //EhiLogger.debug(attrName+" "+wkb);
								 ret.setStringAttribute(prefix+attrName, wkb);
						}else{
							 geomConv.coord2FME(ret,prefix+attrName,value);
						}
					}
					 geomAttrsCollector.add(prefix+attrName);
				}else{
					 ret.setStringAttribute(Main.XTF_GEOMTYPE,"xtf_coord");
					 if(doRichGeometry){
						 IFMEPoint point=null;
						 try{
							 point=Iox2fme.coord2FME(session,value);
							 ret.setGeometry(point);
						 }finally{
							 if(point!=null){
								 point.dispose();
								 point=null;
							 }
						 }
					 }else{
						 ret.setGeometryType(IFMEFeature.FME_GEOM_POINT);
						 addCoord(ret,value);
					 }
				}
			 }
		 }else if(type instanceof ReferenceType){
				IomObject structvalue=iomObj.getattrobj(attrName,0);
				if(structvalue!=null){
					String refoid=structvalue.getobjectrefoid();
					ret.setStringAttribute(prefix+attrName,refoid);
				}
		}else{
		 String value=iomObj.getattrvalue(attrName);
		 if(trimValues){
			 value=StringUtility.purge(value);
		 }
		 if(value!=null){
			 ret.setStringAttribute(prefix+attrName, value);
		 }
		}
		return feedToPipeline;
	}
	private void checkWkb(IFMEFeature fme,String attr,String value)
	throws DataException
	{
		// TODO checkWKB
	}
	private IFMEFactoryPipeline getSurfaceBuilder(AttributeDef geomAttr)
	throws ConfigException
	{
		if(surfaceBuilders.containsKey(geomAttr)){
			return (IFMEFactoryPipeline)surfaceBuilders.get(geomAttr);
		}
		// first use; setup new pipeline
		try{
			String mainTableName=geomAttr.getContainer().getScopedName(null);
			String geomAttrName=geomAttr.getName();
			String lineTableName=mainTableName+"_"+geomAttrName;
			IFMEFactoryPipeline surfaceBuilder=null;
			EhiLogger.traceState("create PolygonBuilder pipeline for "+lineTableName);
			Type attrType=geomAttr.getDomainResolvingAliases();
			if(attrType instanceof AreaType){
				PrecisionDecimal maxOverlaps=((AreaType)attrType).getMaxOverlap();
				if(ili1ConvertArea!=null){
					java.io.File converter=new java.io.File(ili1ConvertArea);
					if(!converter.isAbsolute()){
						if(!converter.exists()){
							java.io.File converterdir=new java.io.File(session.fmeHome(),"plugins/interlis2/converter");
							converter=new java.io.File(converterdir,ili1ConvertArea);
						}
					}

					//factory="MACRO lineTableName "+lineTableName;
					//factory="MACRO mainTableName "+mainTableName;
					//factory="MACRO maxOverlaps "+maxOverlaps;
					IFMEStringArray macros=session.createStringArray();
					macros.append("--lineTableName");macros.append(lineTableName);
					macros.append("--mainTableName");macros.append(mainTableName);
					if(maxOverlaps!=null){
						macros.append("--maxOverlaps");macros.append(Double.toString(maxOverlaps.doubleValue()));
					}
					surfaceBuilder=session.createFactoryPipeline(mainTableName,macros);

					String converterAbsPath=converter.getAbsolutePath();
					EhiLogger.traceState("add converter "+converterAbsPath);
					surfaceBuilder.addFactories(converterAbsPath);
				}else{
					surfaceBuilder=session.createFactoryPipeline(mainTableName,null);
					boolean withLut=false;
					if(withLut){
						IFMEStringArray lut=session.createStringArray();
						lut.append("Lookup");lut.append("Lut");
						lut.append("0");lut.append("ok");
						lut.append("1");lut.append("ok");
						lut.append("");lut.append("notok");
						//lut.append("ENCODED");
						EhiLogger.traceState("factory "+lut);
						//surfaceBuilder.configureFunction(lut);
						session.configure(lut);
					}
					
					String factory=null;
					factory="FACTORY_DEF * IntersectionFactory "
						+" INPUT FEATURE_TYPE "+lineTableName +"  @Transform("+Main.ILI2FME_FORMAT_NAME+",FME_GENERIC)"// +" @Log()"
						+" SELF_INTERSECTION_ONLY"
						+" OUTPUT SEGMENT  FEATURE_TYPE "+lineTableName+"_F01_SEG"
						;
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");
					
					factory="FACTORY_DEF * IntersectionFactory "
						+" INPUT FEATURE_TYPE "+lineTableName +"_F01_SEG"
						+" OUTPUT SEGMENT  FEATURE_TYPE "+lineTableName+"_F02_SEG"
						;
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");

					factory="FACTORY_DEF * IntersectionFactory "
						+" INPUT FEATURE_TYPE "+lineTableName +"_F02_SEG"
						+" OUTPUT SEGMENT  FEATURE_TYPE "+lineTableName+"_F1_SEG"
						;
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");

					factory="FACTORY_DEF * PolygonFactory "
						+" INPUT FEATURE_TYPE "+lineTableName+"_F1_SEG"
						+" END_NODED "
						+" OUTPUT POLYGON FEATURE_TYPE "+lineTableName+"_F2_POLY "
						+" OUTPUT LINE FEATURE_TYPE ERR."+lineTableName+"_POLY_ERROR"
						; 
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");

					factory="FACTORY_DEF * DonutFactory "
						+" INPUT FEATURE_TYPE "+lineTableName+"_F2_POLY "
						+" DROP_HOLES no"
						+" TAG_HOLES no"
						+" OUTPUT DONUT FEATURE_TYPE "+lineTableName+"_F3_DONUT"
						+" OUTPUT POLYGON FEATURE_TYPE "+lineTableName+"_F3_DONUT"
						+" OUTPUT POINT FEATURE_TYPE ERR."+lineTableName+"_F3_POINT_ERROR"
						+" OUTPUT LINE FEATURE_TYPE ERR."+lineTableName+"_F3_LINE_ERROR"
						; 
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");

					factory="FACTORY_DEF * OverlayFactory"
					 	+" INPUT POLYGON FEATURE_TYPE "+lineTableName+"_F3_DONUT"
						+" INPUT POINT   FEATURE_TYPE "+mainTableName+" @Transform("+Main.ILI2FME_FORMAT_NAME+",FME_GENERIC)" //+" @Log()"
						+" OVERLAP_COUNT_ATTRIBUTE _overlaps"
						+" OUTPUT POLYGON FEATURE_TYPE "+mainTableName+"_F4_OVERLAY"
						; 
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");
					
					if(withLut){
						factory="FACTORY_DEF * TestFactory"
							 +" INPUT  FEATURE_TYPE "+mainTableName+"_F4_OVERLAY"
							 +" TEST @Lookup(Lut,_overlaps,ENCODED_ATTR) == ok"
							 +" OUTPUT PASSED FEATURE_TYPE "+mainTableName+"_F5_OK"
							 +" OUTPUT FAILED FEATURE_TYPE ERR."+mainTableName+"_F5_TOOMANYPOINTS"
							 ;
							EhiLogger.traceState("factory "+factory);
							surfaceBuilder.addFactory(factory," ");
							 
							factory="FACTORY_DEF * TestFactory"
							 +" INPUT  FEATURE_TYPE "+mainTableName+"_F5_OK"
							 +" TEST &_overlaps = 0"
							 +" OUTPUT PASSED FEATURE_TYPE ERR."+mainTableName+"_F6_NO_OVERLAPS"
							 +" OUTPUT FAILED FEATURE_TYPE "+mainTableName+" @RemoveAttributes(_overlaps) @Transform(FME_GENERIC,"+Main.ILI2FME_FORMAT_NAME+")"
							 ;
							EhiLogger.traceState("factory "+factory);
							surfaceBuilder.addFactory(factory," ");
					}else{
						factory="FACTORY_DEF * TestFactory"
							 +" INPUT  FEATURE_TYPE "+mainTableName+"_F4_OVERLAY"
							 +" TEST &_overlaps = 0"
							 +" OUTPUT PASSED FEATURE_TYPE ERR."+mainTableName+"_F5_NO_OVERLAPS"
							 +" OUTPUT FAILED FEATURE_TYPE "+mainTableName+"_F5_MANYPOINTS"
							 ;
							EhiLogger.traceState("factory "+factory);
							surfaceBuilder.addFactory(factory," ");
							 
							factory="FACTORY_DEF * TestFactory"
							 +" INPUT  FEATURE_TYPE "+mainTableName+"_F5_MANYPOINTS"
							 +" TEST &_overlaps = 1"
							 +" OUTPUT PASSED FEATURE_TYPE "+mainTableName+" @RemoveAttributes(_overlaps) @Transform(FME_GENERIC,"+Main.ILI2FME_FORMAT_NAME+")"
							 +" OUTPUT FAILED FEATURE_TYPE ERR."+mainTableName+"_F6_TOOMANYPOINTS"
							 ;
							EhiLogger.traceState("factory "+factory);
							surfaceBuilder.addFactory(factory," ");
					}
					 					
				}
			}else if(attrType instanceof SurfaceType){
				PrecisionDecimal maxOverlaps=((SurfaceType)attrType).getMaxOverlap();
				String mainTableRef=ch.interlis.iom_j.itf.ModelUtilities.getHelperTableMainTableRef(geomAttr);
				//EhiLogger.debug("mainTableName "+mainTableName);
				//EhiLogger.debug("lineTableName "+lineTableName);
				//EhiLogger.debug("mainTableRef "+mainTableRef);
				if(ili1ConvertSurface!=null){
					java.io.File converter=new java.io.File(ili1ConvertSurface);
					if(!converter.isAbsolute()){
						if(!converter.exists()){
							java.io.File converterdir=new java.io.File(session.fmeHome(),"plugins/interlis2/converter");
							converter=new java.io.File(converterdir,ili1ConvertSurface);
						}
					}

					//factory="MACRO lineTableName "+lineTableName;
					//factory="MACRO mainTableName "+mainTableName;
					//factory="MACRO maxOverlaps "+maxOverlaps;
					//factory="MACRO mainTableRef "+mainTableRef;
					IFMEStringArray macros=session.createStringArray();
					macros.append("--lineTableName");macros.append(lineTableName);
					macros.append("--mainTableName");macros.append(mainTableName);
					macros.append("--mainTableRef");macros.append(mainTableRef);
					if(maxOverlaps!=null){
						macros.append("--maxOverlaps");macros.append(Double.toString(maxOverlaps.doubleValue()));
					}
					surfaceBuilder=session.createFactoryPipeline(mainTableName,macros);

					String converterAbsPath=converter.getAbsolutePath();
					EhiLogger.traceState("add converter "+converterAbsPath);
					surfaceBuilder.addFactories(converterAbsPath);
				}else{
					surfaceBuilder=session.createFactoryPipeline(mainTableName,null);
					String factory=null;
					
					factory="FACTORY_DEF * IntersectionFactory "
						+" INPUT FEATURE_TYPE "+lineTableName+"  @Transform("+Main.ILI2FME_FORMAT_NAME+",FME_GENERIC)"
						+" SELF_INTERSECTION_ONLY yes"
						+" OUTPUT SEGMENT  FEATURE_TYPE "+lineTableName+"_F1_SEG"
						;
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");
				
					factory="FACTORY_DEF * PolygonFactory "
						+" INPUT FEATURE_TYPE "+lineTableName+"_F1_SEG"
						+" END_NODED"
						+" GROUP_BY "+mainTableRef
						+" OUTPUT POLYGON FEATURE_TYPE "+lineTableName+"_F2_POLY"
						+" OUTPUT LINE FEATURE_TYPE ERR."+lineTableName+"_F2_POLY_ERROR" 
						; 

					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");

					factory="FACTORY_DEF * DonutFactory "
						+" INPUT FEATURE_TYPE "+lineTableName+"_F2_POLY "
						+" DROP_HOLES yes"
						+" GROUP_BY "+mainTableRef
						+" OUTPUT DONUT FEATURE_TYPE "+lineTableName+"_F3_POLY "
						+" OUTPUT POLYGON FEATURE_TYPE "+lineTableName+"_F3_POLY"
						; 
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");

					factory="FACTORY_DEF * ReferenceFactory"
						+" INPUT REFERENCEE FEATURE_TYPE "+lineTableName+"_F3_POLY"
						+" INPUT REFERENCER FEATURE_TYPE "+mainTableName+"  @Transform("+Main.ILI2FME_FORMAT_NAME+",FME_GENERIC)"
						+" REFERENCER_FIELDS xtf_id"
						+" REFERENCEE_FIELDS "+mainTableRef
						+" REFERENCE_INFO GEOM_BUILD_POLYS"
						+" PROCESS_DUPLICATE_REFERENCEES NO"
						+" OUTPUT COMPLETE FEATURE_TYPE "+mainTableName+"  @Transform(FME_GENERIC,"+Main.ILI2FME_FORMAT_NAME+")"
						+" OUTPUT INCOMPLETE FEATURE_TYPE "+mainTableName+"  @Transform(FME_GENERIC,"+Main.ILI2FME_FORMAT_NAME+")"
						+" OUTPUT UNREFERENCED FEATURE_TYPE ERR."+lineTableName+"_REF_ERROR @SupplyAttributes(ENCODED,"+ERRMSG_ATTRIBUTE+",dangling<space>reference)"
						+" OUTPUT DUPLICATE_REFERENCEE FEATURE_TYPE ERR."+lineTableName+"_DUP_ERROR"
						; 
					EhiLogger.traceState("factory "+factory);
					surfaceBuilder.addFactory(factory," ");
				}

			}else{
				throw new IllegalArgumentException("SurfaceOrAreaType expected");
			}
			surfaceBuilders.put(geomAttr,surfaceBuilder);
			return surfaceBuilder;
		}catch(Exception ex){
			throw new ConfigException("failed to build surface pipeline",ex);
		}
	}
	private HashMap typeCache=new HashMap();
	private double getP(LineType type)
	{
		if(typeCache.containsKey(type)){
			return ((Double)typeCache.get(type)).doubleValue();
		}
		double p;
		CoordType coordType=(CoordType)type.getControlPointDomain().getType();
		NumericalType dimv[]=coordType.getDimensions();
		int accuracy=((NumericType)dimv[0]).getMaximum().getAccuracy();
		if(accuracy==0){
			p=0.5;
		}else{
			p=Math.pow(10.0,-accuracy);
			//EhiLogger.debug("accuracy "+accuracy+", p "+p);
		}
		typeCache.put(type,new Double(p));
		return p;
	}
	public IFMEFeature readSchema(IFMEFeature ret) throws Exception {
		try{
			return myreadSchema(ret);
		}catch(Exception ex){
			EhiLogger.logError(ex);
			throw ex;
		}
	}
	public IFMEFeature myreadSchema(IFMEFeature ret) throws Exception {
		// ili2c failed?
		if(iliTd==null){
			return null;
		}
		if(pendingSchemaFeature!=null){
			ret.dispose();
			ret=pendingSchemaFeature;
			pendingSchemaFeature=null;
			return ret;
		}
		// first call?
		if(transferViewablei==null){
			// create XTF_TRANSFER class
			if(formatFeatureTypeIdx==FORMAT_FEATURETYPE_XTFTRANSFER){
				ret.setFeatureType(Main.XTF_TRANSFER);
				ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
				ret.setSequencedAttribute(Main.XTF_OIDSPACE+"{}."+Main.XTF_OIDNAME,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_OIDSPACE+"{}."+Main.XTF_OIDDOMAIN,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_COMMENT,Main.ILINAME_TYPE);
				formatFeatureTypeIdx++;
				return ret;	
			}
			// create XTF_BASKETS class
			if(formatFeatureTypeIdx==FORMAT_FEATURETYPE_XTFBASKETS){
				ret.setFeatureType(Main.XTF_BASKETS);
				ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
				ret.setSequencedAttribute(Main.XTF_TOPIC,getIliQNameType());
				ret.setSequencedAttribute(Main.XTF_ID,Main.ID_TYPE);
				ret.setSequencedAttribute(Main.XTF_STARTSTATE,Main.STATE_TYPE);
				ret.setSequencedAttribute(Main.XTF_ENDSTATE,Main.STATE_TYPE);
				ret.setSequencedAttribute(Main.XTF_CONSISTENCY,Main.CONSISTENCY_TYPE);
				formatFeatureTypeIdx++;
				return ret;	
			}
			if(formatFeatureTypeIdx==FORMAT_FEATURETYPE_XTFDELETEOBJECT){
				ret.setFeatureType(Main.XTF_DELETEOBJECT);
				ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
				ret.setSequencedAttribute(Main.XTF_ID,Main.ID_TYPE);
				ret.setSequencedAttribute(Main.XTF_BASKET,Main.ID_TYPE);
				formatFeatureTypeIdx++;
				return ret;	
			}
			if(formatFeatureTypeIdx==FORMAT_FEATURETYPE_XTFERRORS){
				ret.setFeatureType(Main.XTF_ERRORS);
				ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
				ret.setSequencedAttribute(Main.XTF_ERRORS_MESSAGE,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_ERRORS_ILINAME,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_ERRORS_TID+"{}",Main.ID_TYPE);
				formatFeatureTypeIdx++;
				return ret;	
			}
			if(formatFeatureTypeIdx==FORMAT_FEATURETYPE_ENUMS && createEnumTypes==CreateEnumFeatureTypes.SINGLETYPE){
				ret.setFeatureType(Main.XTF_ENUMS);
				ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
				ret.setSequencedAttribute(Main.XTF_ENUMTHIS,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_ENUMBASE,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_ENUMILICODE,Main.ILINAME_TYPE);
				ret.setSequencedAttribute(Main.XTF_ENUMITFCODE,Main.XTF_ENUMITFCODE_TYPE);
				ret.setSequencedAttribute(Main.XTF_ENUMSEQ,Main.XTF_ENUMSEQ_TYPE);
				formatFeatureTypeIdx++;
				return ret;	
			}
			if(formatFeatureTypeIdx>=FORMAT_FEATURETYPE_ENUMS && createEnumTypes==CreateEnumFeatureTypes.ONETYPEPERENUMDEF){
				if(formatFeatureTypeIdx==FORMAT_FEATURETYPE_ENUMS){
					enumDefi=enumDefs.iterator();
				}
				if(enumDefi!=null){
					if(enumDefi.hasNext()){
						Object enumDef=enumDefi.next();
						String enumTypeName = mapEnumDefName(enumDef);
						ret.setFeatureType(enumTypeName);
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
						ret.setSequencedAttribute(Main.XTF_ENUMILICODE,Main.ILINAME_TYPE);
						ret.setSequencedAttribute(Main.XTF_ENUMITFCODE,Main.XTF_ENUMITFCODE_TYPE);
						ret.setSequencedAttribute(Main.XTF_ENUMSEQ,Main.XTF_ENUMSEQ_TYPE);
						formatFeatureTypeIdx++;
						return ret;	
					}else{
						enumDefi=null;
					}
				}
			}
			// init class iterator
			transferViewablei=transferViewables.keySet().iterator();
			seenFmeTypes=new HashSet();
		}
		// more classes?
		while(transferViewablei.hasNext()){
			String iliQName=(String)transferViewablei.next();
			//EhiLogger.debug("iliQName "+iliQName);
			Element v=(Element)tag2class.get(iliQName);
			if((v instanceof Table) && !((Table)v).isIdentifiable()){
				// skip structures
				continue;
			}
			if((v instanceof AssociationDef) && ((AssociationDef)v).isLightweight()){
				// skip embedded assocs
				continue;
			}
			ViewableWrapper wrapper=(ViewableWrapper)transferViewables.get(iliQName);
			// FME feature type already seen?
			if(seenFmeTypes.contains(wrapper)){
				// skip already seen FME feature type
				continue;
			}
			AttributeDef geomattr=wrapper.getGeomAttr4FME();
			mapFeatureType(geomattr,ret,wrapper,null);						
			if(geomattr==null){
				ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
			}else{
				Type type=geomattr.getDomainResolvingAliases();
				if(formatMode==MODE_XTF || formatMode==MODE_ITF2){
					if (type instanceof PolylineType){
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_polyline");
					}else if(type instanceof SurfaceType){
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_surface");
					}else if(type instanceof AreaType){
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_area");
					}else if(type instanceof CoordType){
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_coord");
					}else{
						throw new IllegalStateException("!(type instanceof geomType)");
					}
				}else if(formatMode==MODE_ITF){
					if (type instanceof PolylineType){
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_polyline");
					}else if(type instanceof SurfaceType){
						// is main table?
						if(!wrapper.isHelper()){
							// main table
							ret.setSequencedAttribute("fme_geometry{0}", "xtf_surface");
							if(createLineTableFeatures){
								if(!skipPolygonBuilding){
									// add main table (MT) feature type
									pendingSchemaFeature=session.createFeature();
									ret.clone(pendingSchemaFeature);
									pendingSchemaFeature.setSequencedAttribute("fme_geometry{0}", "xtf_none");
									String featureType=ret.getFeatureType();
									pendingSchemaFeature.setFeatureType(featureType+"_MT");
								}else{
									// change feature type that represents the main table
									ret.setSequencedAttribute("fme_geometry{0}", "xtf_none");
									String featureType=ret.getFeatureType();
									ret.setFeatureType(featureType+"_MT");
								}
							}
						}else{
							// helper table
							if(createLineTableFeatures){
								String featureType=ret.getFeatureType();
								ret.setFeatureType(featureType+"_LT");
								ret.setSequencedAttribute("fme_geometry{0}", "xtf_polyline");
								//add reference attr to main table
								//String fkName=wrapper.getGeomAttr4FME().getContainer().getName();
								String fkName=ch.interlis.iom_j.itf.ModelUtilities.getHelperTableMainTableRef(wrapper.getGeomAttr4FME());
								ret.setSequencedAttribute(fkName,Main.ID_TYPE);
								// add line attrs
								SurfaceType surfaceType=(SurfaceType)type;
								Table lineAttrTable=surfaceType.getLineAttributeStructure();
								if(lineAttrTable!=null){
								    Iterator attri = lineAttrTable.getAttributes ();
								    while(attri.hasNext()){
								    	AttributeDef attr=(AttributeDef)attri.next();
										mapAttributeDef(null, ret, attr, null);
								    }
								}
							}else{
								continue; //skip it
							}
						}
					}else if(type instanceof AreaType){
						// if main table?
						if(!wrapper.isHelper()){
							ret.setSequencedAttribute("fme_geometry{0}", "xtf_area");
							if(createLineTableFeatures){
								if(!skipPolygonBuilding){
									// add main table (MT) feature type
									pendingSchemaFeature=session.createFeature();
									ret.clone(pendingSchemaFeature);
									pendingSchemaFeature.setSequencedAttribute("fme_geometry{0}", "xtf_coord");
									String featureType=ret.getFeatureType();
									pendingSchemaFeature.setFeatureType(featureType+"_MT");
								}else{
									// change feature type that represents the main table
									ret.setSequencedAttribute("fme_geometry{0}", "xtf_coord");
									String featureType=ret.getFeatureType();
									ret.setFeatureType(featureType+"_MT");
								}
							}
						}else{
							// helper table
							if(createLineTableFeatures){
								ret.setSequencedAttribute("fme_geometry{0}", "xtf_polyline");
								String featureType=ret.getFeatureType();
								ret.setFeatureType(featureType+"_LT");
								// add line attrs
								AreaType areaType=(AreaType)type;
								Table lineAttrTable=areaType.getLineAttributeStructure();
								if(lineAttrTable!=null){
								    Iterator attri = lineAttrTable.getAttributes ();
								    while(attri.hasNext()){
								    	AttributeDef attr=(AttributeDef)attri.next();
										mapAttributeDef(null, ret, attr, null);
								    }
								}
							}else{
								continue; //skip it
							}
						}
					}else if(type instanceof CoordType){
						ret.setSequencedAttribute("fme_geometry{0}", "xtf_coord");
					}else{
						throw new IllegalStateException("!(type instanceof geomType)");
					}
				}
			}
			seenFmeTypes.add(wrapper);
			return ret;
		}
		transferViewablei=null;
		return null;
	}
	private String mapEnumDefName(Object enumDef) {
		String enumTypeName=null;
		if(enumDef instanceof AttributeDef){
			AttributeDef attr=(AttributeDef)enumDef;
			enumTypeName=attr.getContainer().getScopedName(null)+"."+attr.getName();
		}else if(enumDef instanceof Domain){
			Domain domain=(Domain)enumDef;
			enumTypeName=domain.getScopedName(null);
		}else{
			throw new IllegalStateException();
		}
		return enumTypeName;
	}
	private void mapFeatureType(AttributeDef geomAttr,IFMEFeature ret,ViewableWrapper wrapper,String attrNamePrefix)
	{
		boolean isStruct=attrNamePrefix!=null;
		if(!isStruct)attrNamePrefix="";
		List attrv=wrapper.getAttrv();
		if(!isStruct){
			ret.setFeatureType(wrapper.getFmeFeatureType());
			ret.setSequencedAttribute(Main.XTF_ID,Main.ID_TYPE);
			// do not add the following attributes, so they appear only in the 
			// Format Attribute tab in the workbench
			// see also WORKBENCH_EXPOSABLE_ATTRIBUTES in metafile (.fmf)
			//ret.setStringAttribute(Main.XTF_BASKET,Main.ID_TYPE);
			//ret.setStringAttribute(Main.XTF_GEOMATTR,Main.ILINAME_TYPE);
			//ret.setStringAttribute(Main.XTF_CLASS,getIliQNameType());
			//ret.setStringAttribute(Main.XTF_CONSISTENCY,Main.CONSISTENCY_TYPE);
			//ret.setStringAttribute(Main.XTF_OPERATION,Main.OPERATION_TYPE);
		}else{
			ret.setSequencedAttribute(attrNamePrefix+Main.XTF_CLASS,getIliQNameType());
		}
		for(int i=0;i<attrv.size();i++){
			ViewableTransferElement attro=(ViewableTransferElement)attrv.get(i);
			if(attro.obj instanceof AttributeDef){
				AttributeDef attr=(AttributeDef)attro.obj;
				mapAttributeDef(geomAttr, ret, attr, attrNamePrefix);
			}else if(attro.obj instanceof RoleDef){
				RoleDef role=(RoleDef)attro.obj;
				ret.setSequencedAttribute(attrNamePrefix+role.getName(),Main.ID_TYPE);
				if(attro.embedded){
					AssociationDef assocClass=(AssociationDef)role.getContainer();
					if(assocClass.getAttributes().hasNext() || assocClass.getLightweightAssociations().iterator().hasNext()){
						Viewable rootAssocClass=ModelUtility.getRoot(assocClass);
						if(rootAssocClass==null){
						 rootAssocClass=assocClass;
						}
						// EhiLogger.debug(attr.getScopedName(null)+", "+rootClass.getScopedName(null));
						mapFeatureType(null,ret,(ViewableWrapper)transferViewables.get(rootAssocClass.getScopedName(null)),attrNamePrefix+role.getName()+"{}.");						
					}
				}
			}
		}
	}
	private void mapAttributeDef(AttributeDef geomAttr, IFMEFeature ret, AttributeDef attr, String attrNamePrefix) {
		if(attrNamePrefix==null){
			attrNamePrefix="";
		}
		Type type=attr.getDomainResolvingAliases();
		if (type instanceof PolylineType 
			|| type instanceof SurfaceOrAreaType
			|| type instanceof CoordType){
				if((type instanceof CoordType) && ((CoordType)type).getDimensions().length==1){
					String numType = mapNumericType((NumericType)((CoordType)type).getDimensions()[0]);
					ret.setSequencedAttribute(attrNamePrefix+attr.getName(),numType);
				}else{
					if(geomAttr==attr){
						// process it as FME geometry
						// don't add it as a FME attribute
					}else{
						ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_buffer");
					}
				}
		}else if(type instanceof CompositionType){
			Viewable structClass=((CompositionType)type).getComponentType();
			Viewable rootClass=ModelUtility.getRoot(structClass);
			if(rootClass==null){
			 rootClass=structClass;
			}
			// EhiLogger.debug(attr.getScopedName(null)+", "+rootClass.getScopedName(null));
			mapFeatureType(null,ret,(ViewableWrapper)transferViewables.get(rootClass.getScopedName(null)),attrNamePrefix+attr.getName()+"{}.");						
		}else{
			// TODO set attribute type
			if (isBoolean(iliTd,attr)) {
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_boolean");
			}else if (isIli1Date(iliTd,attr)) {
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_date");
			}else if (isIli2Date(iliTd,attr)) {
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_date");
			}else if (isIli2DateTime(iliTd,attr)) {
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_datetime");
			}else if (isIli2Time(iliTd,attr)) {
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_time");
			}else if (type instanceof ReferenceType){
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),Main.ID_TYPE);
			}else if (type instanceof BasketType){
				// skip it; type no longer exists in ili 2.3
			}else if(type instanceof EnumerationType){
				if(ili1EnumAsItfCode){
					ret.setSequencedAttribute(attrNamePrefix+attr.getName(),Main.XTF_ENUMITFCODE_TYPE);
				}else{
					ret.setSequencedAttribute(attrNamePrefix+attr.getName(),Main.ILINAME_TYPE);
				}
			}else if(type instanceof NumericType){
				if(type.isAbstract()){
				}else{
					String numType = mapNumericType((NumericType)type);
					ret.setSequencedAttribute(attrNamePrefix+attr.getName(),numType);
				}
			}else if(type instanceof TextType){
				if(((TextType)type).getMaxLength()>0){
					ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_char("+((TextType)type).getMaxLength()+")");
				}else{
					ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_buffer");
				}
			}else if(type instanceof BlackboxType){
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_buffer");
			}else{
				ret.setSequencedAttribute(attrNamePrefix+attr.getName(),"xtf_char(255)");
			}
			
		}
	}
	private String mapNumericType(NumericType type) {
		String numType=null;
		PrecisionDecimal min=((NumericType)type).getMinimum();
		PrecisionDecimal max=((NumericType)type).getMaximum();
		if(min.getAccuracy()>0){
			int size=Math.max(min.toString().length(),max.toString().length());
			int precision=min.getAccuracy();
			//EhiLogger.debug("attr "+ attr.getName()+", maxStr <"+maxStr+">, size "+Integer.toString(size)+", precision "+Integer.toString(precision));
			numType="xtf_decimal("+size+","+precision+")";
		}else{
			if(min.compareTo(new PrecisionDecimal(Integer.MIN_VALUE))>=0 && max.compareTo(new PrecisionDecimal(Integer.MAX_VALUE))<=0){
				numType="xtf_int32";
			}else{
				int size=Math.max(min.toString().length(),max.toString().length());
				numType="xtf_decimal("+size+","+0+")";
			}
		}
		return numType;
	}
	static public boolean isBoolean(TransferDescription td,Type type){
		while(type instanceof TypeAlias) {
			if (((TypeAlias) type).getAliasing() == td.INTERLIS.BOOLEAN) {
				return true;
			}
			type=((TypeAlias) type).getAliasing().getType();
		}
		
		return false;
	}
	static public boolean isBoolean(TransferDescription td,AttributeDef attr){
		if (attr.getDomain() instanceof TypeAlias && isBoolean(td,attr.getDomain())) {
			return true;
		}
		return false;
		
	}
	static public boolean isIli1Date(TransferDescription td,AttributeDef attr){
		if (attr.getDomain() instanceof TypeAlias){
			Type type=attr.getDomain();
			while(type instanceof TypeAlias) {
				if (((TypeAlias) type).getAliasing() == td.INTERLIS.INTERLIS_1_DATE) {
					return true;
				}
				type=((TypeAlias) type).getAliasing().getType();
			}
		}
		return false;
	}
	static public boolean isIli2Date(TransferDescription td,AttributeDef attr){
		Type type=attr.getDomain();
		if (type instanceof TypeAlias){
			while(type instanceof TypeAlias) {
				if (((TypeAlias) type).getAliasing() == td.INTERLIS.XmlDate) {
					return true;
				}
				type=((TypeAlias) type).getAliasing().getType();
			}
		}
		if(type instanceof FormattedType){
			FormattedType ft=(FormattedType)type;
			if(ft.getDefinedBaseDomain()== td.INTERLIS.XmlDate){
				return true;
			}
		}
		return false;
	}
	static public boolean isIli2Time(TransferDescription td,AttributeDef attr){
		Type type=attr.getDomain();
		if (type instanceof TypeAlias){
			while(type instanceof TypeAlias) {
				if (((TypeAlias) type).getAliasing() == td.INTERLIS.XmlTime) {
					return true;
				}
				type=((TypeAlias) type).getAliasing().getType();
			}
		}
		if(type instanceof FormattedType){
			FormattedType ft=(FormattedType)type;
			if(ft.getDefinedBaseDomain()== td.INTERLIS.XmlTime){
				return true;
			}
		}
		return false;
	}
	static public boolean isIli2DateTime(TransferDescription td,AttributeDef attr){
		Type type=attr.getDomain();
		if (type instanceof TypeAlias){
			while(type instanceof TypeAlias) {
				if (((TypeAlias) type).getAliasing() == td.INTERLIS.XmlDateTime) {
					return true;
				}
				type=((TypeAlias) type).getAliasing().getType();
			}
		}
		if(type instanceof FormattedType){
			FormattedType ft=(FormattedType)type;
			if(ft.getDefinedBaseDomain()== td.INTERLIS.XmlDateTime){
				return true;
			}
		}
		return false;
	}
	
	public boolean spatialEnabled() throws Exception {
		return false;
	}

	public void setConstraints(IFMEFeature arg0) throws Exception {
	}
	
	//
	// helpers
	//
	private void addCoord(IFMEFeature ret,IomObject value)
	throws DataException
	{
		if(value!=null){
			String c1=value.getattrvalue("C1");
			String c2=value.getattrvalue("C2");
			String c3=value.getattrvalue("C3");
			double xCoord;
			try{
				xCoord = Double.parseDouble(c1);
			}catch(Exception ex){
				throw new DataException("failed to read C1 <"+c1+">",ex);
			}
			double yCoord;
			try{
				yCoord = Double.parseDouble(c2);
			}catch(Exception ex){
				throw new DataException("failed to read C2 <"+c2+">",ex);
			}
			if(c3==null){
				ret.setDimension(IFMEFeature.FME_TWO_D);
				ret.add2DCoordinate(xCoord, yCoord);
			}else{
				double zCoord;
				try{
					zCoord = Double.parseDouble(c3);
				}catch(Exception ex){
					throw new DataException("failed to read C3 <"+c3+">",ex);
				}
				ret.setDimension(IFMEFeature.FME_THREE_D);
				ret.add3DCoordinate(xCoord, yCoord, zCoord);
			}
		}
	}
	private void addArc(IFMEFeature ret,IomObject value,double p)
	throws DataException
	{
		if(value!=null){
			String c1=value.getattrvalue("C1");
			String c2=value.getattrvalue("C2");
			String c3=value.getattrvalue("C3");
			String a1=value.getattrvalue("A1");
			String a2=value.getattrvalue("A2");
			double pt2_re;
			try{
				pt2_re = Double.parseDouble(c1);
			}catch(Exception ex){
				throw new DataException("failed to read C1 <"+c1+">",ex);
			}
			double pt2_ho;
			try{
				pt2_ho = Double.parseDouble(c2);
			}catch(Exception ex){
				throw new DataException("failed to read C2 <"+c2+">",ex);
			}
			double arcPt_re;
			try{
				arcPt_re = Double.parseDouble(a1);
			}catch(Exception ex){
				throw new DataException("failed to read A1 <"+a1+">",ex);
			}
			double arcPt_ho;
			try{
				arcPt_ho = Double.parseDouble(a2);
			}catch(Exception ex){
				throw new DataException("failed to read A2 <"+a2+">",ex);
			}
			if(p==0.0){
				ret.add2DCoordinate(arcPt_re, arcPt_ho);
				ret.add2DCoordinate(pt2_re, pt2_ho);
				return;
			}
			int lastCoord=ret.numCoords();
			double p1[]=null;
			try{
				p1=ret.get2DCoordinate(lastCoord-1);
			}catch(FMEException ex)
			{
				EhiLogger.logError(ex);
				ret.add2DCoordinate(arcPt_re, arcPt_ho);
				ret.add2DCoordinate(pt2_re, pt2_ho);
			}
			double pt1_re=p1[0];
			double pt1_ho=p1[1];
			//EhiLogger.debug("pt1 "+pt1_re+", "+pt1_ho);
			//EhiLogger.debug("arc "+arcPt_re+", "+arcPt_ho);
			//EhiLogger.debug("pt2 "+pt2_re+", "+pt2_ho);
			/*
			if(c3==null){
				ret.setDimension(IFMEFeature.FME_TWO_D);
				ret.add2DCoordinate(p2_x, p2_y);
			}else{
				double zCoord = Double.parseDouble(c3);
				ret.setDimension(IFMEFeature.FME_THREE_D);
				ret.add3DCoordinate(p2_x, p2_y, zCoord);
			}
			*/
			// letzter Punkt ein Bogenzwischenpunkt?
		
			// Zwischenpunkte erzeugen

			// Distanz zwischen Bogenanfanspunkt und Zwischenpunkt
			double a=dist(pt1_re,pt1_ho,arcPt_re,arcPt_ho);
			// Distanz zwischen Zwischenpunkt und Bogenendpunkt 
			double b=dist(arcPt_re,arcPt_ho,pt2_re,pt2_ho);

			// Zwischenpunkte erzeugen, so dass maximale Pfeilh�he nicht 
			// �berschritten wird
			// Distanz zwischen Bogenanfanspunkt und Bogenendpunkt 
			double c=dist(pt1_re,pt1_ho,pt2_re,pt2_ho);
			// Radius bestimmen
			double s=(a+b+c)/2.0;
			double ds=Math.atan2(pt2_re-arcPt_re,pt2_ho-arcPt_ho)-Math.atan2(pt1_re-arcPt_re,pt1_ho-arcPt_ho);
			double rSign=(Math.sin(ds)>0.0)?-1.0:1.0;
			double r=a*b*c/4.0/Math.sqrt(s*(s-a)*(s-b)*(s-c))*rSign;
			// Kreismittelpunkt
			double thetaM=Math.atan2(arcPt_re-pt1_re,arcPt_ho-pt1_ho)+Math.acos(a/2.0/r);
			double reM=pt1_re+r*Math.sin(thetaM);
			double hoM=pt1_ho+r*Math.cos(thetaM);

			// mindest Winkelschrittweite
			double theta=2*Math.acos(1-p/Math.abs(r));

			if(a>2*p){
				// Zentriwinkel zwischen pt1 und arcPt
				double alpha=2.0*Math.asin(a/2.0/Math.abs(r));
				// anzahl Schritte
				int alphan=(int)Math.ceil(alpha/theta);
				// Winkelschrittweite
				double alphai=alpha/(alphan*(r>0.0?1:-1));
				double ri=Math.atan2(pt1_re-reM,pt1_ho-hoM);
				for(int i=1;i<alphan;i++){
					ri += alphai;
					double pti_re=reM + Math.abs(r) * Math.sin(ri);
					double pti_ho=hoM + Math.abs(r) * Math.cos(ri);
					ret.add2DCoordinate(pti_re, pti_ho);
				}
			}

			ret.add2DCoordinate(arcPt_re, arcPt_ho);

			if(b>2*p){
				// Zentriwinkel zwischen arcPt und pt2
				double beta=2.0*Math.asin(b/2.0/Math.abs(r));
				// anzahl Schritte
				int betan=(int)Math.ceil((beta/theta));
				// Winkelschrittweite
				double betai=beta/(betan*(r>0.0?1:-1));
				double ri=Math.atan2(arcPt_re-reM,arcPt_ho-hoM);
				for(int i=1;i<betan;i++){
					ri += betai;
					double pti_re=reM + Math.abs(r) * Math.sin(ri);
					double pti_ho=hoM + Math.abs(r) * Math.cos(ri);
					ret.add2DCoordinate(pti_re, pti_ho);
				}
			}
			ret.add2DCoordinate(pt2_re, pt2_ho);
		}
	}
	private static double sqr(double x)
	{
		return x*x;
	}
	private static double dist(double re1,double ho1,double re2,double ho2)
	{
		double ret;
		ret=Math.hypot(re2-re1,ho2-ho1);
		return ret;
	}
	private void setPolyline(IFMEFeature ret,IomObject obj,boolean isSurfaceOrArea,double p)
	throws DataException
	{
		if(obj==null){
			return;
		}
		// is POLYLINE?
		if(!isSurfaceOrArea){
			ret.setGeometryType(IFMEFeature.FME_GEOM_LINE);
		}
		if(isSurfaceOrArea){
			IomObject lineattr=obj.getattrobj("lineattr",0);
			if(lineattr!=null){
				//writeAttrs(out,lineattr);
				EhiLogger.logAdaption("Lineattributes not supported by FME; ignored");							
			}
		}
		boolean clipped=obj.getobjectconsistency()==IomConstants.IOM_INCOMPLETE;
		for(int sequencei=0;sequencei<obj.getattrvaluecount("sequence");sequencei++){
			if(clipped){
				//out.startElement(tags::get_CLIPPED(),0,0);
			}else{
				// an unclipped polyline should have only one sequence element
				if(sequencei>0){
					EhiLogger.logError("unclipped polyline with multi 'sequence' elements");
					break;
				}
			}
			IomObject sequence=obj.getattrobj("sequence",sequencei);
			for(int segmenti=0;segmenti<sequence.getattrvaluecount("segment");segmenti++){
				IomObject segment=sequence.getattrobj("segment",segmenti);
				//EhiLogger.debug("segmenttag "+segment.getobjecttag());
				if(segment.getobjecttag().equals("COORD")){
					// COORD
					addCoord(ret,segment);
				}else if(segment.getobjecttag().equals("ARC")){
					// ARC
					addArc(ret,segment,p);
				}else{
					// custum line form
					EhiLogger.logAdaption("custom line form not supported by FME; ignored");
					//out.startElement(segment->getTag(),0,0);
					//writeAttrs(out,segment);
					//out.endElement(/*segment*/);
				}

			}
			if(clipped){
				//out.endElement(/*CLIPPED*/);
			}
		}
	}
	private void setSurface(IFMEFeature ret,IomObject obj,SurfaceOrAreaType type)
	throws DataException
	{
		if(obj==null){
			return;
		}
		ret.setGeometryType(IFMEFeature.FME_GEOM_DONUT);
		//IFMEFeatureVector bndries=session.createFeatureVector();
		boolean clipped=obj.getobjectconsistency()==IomConstants.IOM_INCOMPLETE;
		for(int surfacei=0;surfacei<obj.getattrvaluecount("surface");surfacei++){
			if(clipped){
				//out.startElement("CLIPPED",0,0);
			}else{
				// an unclipped surface should have only one surface element
				if(surfacei>0){
					EhiLogger.logError("unclipped surface with multi 'surface' elements");
					break;
				}
			}
			IomObject surface=obj.getattrobj("surface",surfacei);
			for(int boundaryi=0;boundaryi<surface.getattrvaluecount("boundary");boundaryi++){
				IomObject boundary=surface.getattrobj("boundary",boundaryi);
				//IFMEFeature fmeLine=session.createFeature();
				for(int polylinei=0;polylinei<boundary.getattrvaluecount("polyline");polylinei++){
					IomObject polyline=boundary.getattrobj("polyline",polylinei);
					setPolyline(ret,polyline,true,getP(type));
				}
				//bndries.append(fmeLine);
			}
			if(clipped){
				//out.endElement(/*CLIPPED*/);
			}
		}
	}
	private void setSurface(IFMEFeature ret,IomObject obj)
	throws DataException
	{
		if(obj==null){
			return;
		}
		ret.setGeometryType(IFMEFeature.FME_GEOM_DONUT);
		//IFMEFeatureVector bndries=session.createFeatureVector();
		boolean clipped=obj.getobjectconsistency()==IomConstants.IOM_INCOMPLETE;
		for(int surfacei=0;surfacei<obj.getattrvaluecount("surface");surfacei++){
			if(clipped){
				//out.startElement("CLIPPED",0,0);
			}else{
				// an unclipped surface should have only one surface element
				if(surfacei>0){
					EhiLogger.logError("unclipped surface with multi 'surface' elements");
					break;
				}
			}
			IomObject surface=obj.getattrobj("surface",surfacei);
			for(int boundaryi=0;boundaryi<surface.getattrvaluecount("boundary");boundaryi++){
				IomObject boundary=surface.getattrobj("boundary",boundaryi);
				//IFMEFeature fmeLine=session.createFeature();
				for(int polylinei=0;polylinei<boundary.getattrvaluecount("polyline");polylinei++){
					IomObject polyline=boundary.getattrobj("polyline",polylinei);
					setPolyline(ret,polyline,true,0.0);
				}
				//bndries.append(fmeLine);
			}
			if(clipped){
				//out.endElement(/*CLIPPED*/);
			}
		}
	}
	/** list of not yet processed struct values
	 */
	private ArrayList structQueue=null;
	private void enqueStructValue(int parentSqlId,String parentSqlType,String parentSqlAttr,IomObject struct,int structi)
	{
		structQueue.add(new StructWrapper(parentSqlId,parentSqlType,parentSqlAttr,struct,structi));
	}	
	private FmeLogListener listener=null;
	private void cleanup(){
		//EhiLogger.debug("cleanup");
		if(geomConv!=null){
			geomConv.dispose();
		}
		if(geomAttrIterator!=null){
			geomAttrIterator.dispose();
			geomAttrIterator=null;
		}
		if(ioxReader!=null){
			try{
				ioxReader.close();
			}catch(IoxException ex){
				EhiLogger.logError(ex);
			}
			ioxReader=null;
		}
		if(inputFile!=null){
			try{
				inputFile.close();
			}catch(IOException ex){
				EhiLogger.logError(ex);
			}
			inputFile=null;
		}
		if(listener!=null){
			Main.endLogging(listener);
			listener=null;
		}
	}
	private String getIliQNameType()
	{
		return "xtf_char("+Integer.toString(iliQNameSize)+")";
	}
	private ArrayList enumDefs=null; // array<Domain | AttributeDef>
	private Iterator enumDefi=null; 
	/** collect definitions of enumerations in all compiled models.
	 */
	private void collectEnums(TransferDescription td)
	{
		enumDefs=new ArrayList();
		enumDefi=null;
		Iterator modeli = td.iterator();
		while (modeli.hasNext()) {
			Object modelo = modeli.next();
			if (modelo instanceof Model){
					Model model = (Model) modelo;
					Iterator topici = model.iterator();
					while (topici.hasNext()) {
						Object topico = topici.next();
						if (topico instanceof Topic) {
							Topic topic = (Topic) topico;
							   Iterator classi = topic.iterator();
							   while (classi.hasNext())
							   {
							     Element ele = (Element)classi.next();
							     if(ele instanceof AbstractClassDef){
										AbstractClassDef aclass=(AbstractClassDef)ele;
										Iterator iter = aclass.iterator();
										while (iter.hasNext()) {
											Object obj = iter.next();
											if (obj instanceof AttributeDef) {
												AttributeDef attr = (AttributeDef)obj;
												if(attr.getExtending()==null && !attr.isTransient()){
													// define only new attrs (==not EXTENDED)
													if(attr.getDomain() instanceof EnumerationType){
														enumDefs.add(attr);
													}
												}
											}
										}
								 }else if(ele instanceof Domain){
										if(((Domain)ele).getType() instanceof EnumerationType){
											enumDefs.add(ele);
										}
								 }
							   }
						}else if(topico instanceof AbstractClassDef){
							AbstractClassDef aclass=(AbstractClassDef)topico;
							Iterator iter = aclass.iterator();
							while (iter.hasNext()) {
								Object obj = iter.next();
								if (obj instanceof AttributeDef) {
									AttributeDef attr = (AttributeDef)obj;
									if(attr.getExtending()==null && !attr.isTransient()){
										// define only new attrs (==not EXTENDED)
										if(attr.getDomain() instanceof EnumerationType){
											enumDefs.add(attr);
										}
									}
								}
							}
							
						}else if(topico instanceof Domain){
							if(((Domain)topico).getType() instanceof EnumerationType){
								enumDefs.add(topico);
							}
						}
					}
			}
		}
		
	}
	/** returns null, if no more enum eles.
	 */
	private Element currentEnumDef=null; // Domain | AttributeDef
	private int currentEnumItfCode=0;
	private int currentEnumSeq=0;
	private Iterator currentEnumElementIterator=null;
	private IFMEFeature processEnums(IFMEFeature ret){
		
		if(enumDefi==null){
			enumDefi=enumDefs.iterator();
		}
		while(enumDefi.hasNext() || (currentEnumElementIterator!=null && currentEnumElementIterator.hasNext())){
			// no more elements in current domain/attr?
			if(currentEnumElementIterator==null || !currentEnumElementIterator.hasNext()){
				// get next domain/attr
				currentEnumDef=(Element)enumDefi.next();
			}
			String enumTypeName = mapEnumDefName(currentEnumDef);
			EnumerationType type=null;
			Element base=null;
			if(currentEnumDef instanceof AttributeDef){
				type=(EnumerationType)((AttributeDef)currentEnumDef).getDomain();
				base=((AttributeDef)currentEnumDef).getExtending();
			}else if(currentEnumDef instanceof Domain){
				type=(EnumerationType)((Domain)currentEnumDef).getType();
				base=((Domain)currentEnumDef).getExtending();
			}else{
				throw new IllegalStateException();
			}
			java.util.ArrayList ev=new java.util.ArrayList();
			boolean isOrdered=type.isOrdered();
			String baseClass=null;
			if(base!=null){
				baseClass=mapEnumDefName(base);
			}

			// no more elements in current domain/attr?
			if(currentEnumElementIterator==null || !currentEnumElementIterator.hasNext()){
				// setup new element iterator
				ch.interlis.iom_j.itf.ModelUtilities.buildEnumList(ev,"",type.getConsolidatedEnumeration());
				currentEnumItfCode=0;
				currentEnumSeq=0;
				currentEnumElementIterator=ev.iterator();
			}
			// more elements?
			if(currentEnumElementIterator.hasNext()){
				String ele=(String)currentEnumElementIterator.next();
				if(createEnumTypes==CreateEnumFeatureTypes.SINGLETYPE){
					ret.setFeatureType(Main.XTF_ENUMS);
				}else{
					ret.setFeatureType(enumTypeName);
				}
				ret.setStringAttribute(Main.XTF_ENUMILICODE,ele);
				ret.setStringAttribute(Main.XTF_ENUMITFCODE,Integer.toString(currentEnumItfCode));
				if(isOrdered){
					ret.setStringAttribute(Main.XTF_ENUMSEQ,Integer.toString(currentEnumSeq));
				}
				if(createEnumTypes==CreateEnumFeatureTypes.SINGLETYPE){
					ret.setStringAttribute(Main.XTF_ENUMTHIS,enumTypeName);
					if(baseClass!=null){
						ret.setStringAttribute(Main.XTF_ENUMBASE,baseClass);
					}
				}
				currentEnumItfCode++;
				currentEnumSeq++;
				return ret;
			}
			
		}
		
		// no more enum eles
		return null;	
		
	}
	public boolean getProperties(String propertyCategory, ArrayList values) throws Exception {
		values.clear();
		return false;
	}
}
