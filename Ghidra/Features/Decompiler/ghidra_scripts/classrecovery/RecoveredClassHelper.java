/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//DO NOT RUN. THIS IS NOT A SCRIPT! THIS IS A CLASS THAT IS USED BY SCRIPTS.
package classrecovery;

import java.util.*;
import java.util.stream.Collectors;

import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.cmd.label.AddLabelCmd;
import ghidra.app.cmd.label.SetLabelPrimaryCmd;
import ghidra.app.plugin.core.analysis.ReferenceAddressPair;
import ghidra.app.plugin.core.decompile.actions.FillOutStructureCmd;
import ghidra.app.plugin.core.decompile.actions.FillOutStructureCmd.OffsetPcodeOpPair;
import ghidra.app.plugin.core.navigation.locationreferences.LocationReference;
import ghidra.app.plugin.core.navigation.locationreferences.ReferenceUtils;
import ghidra.app.util.NamespaceUtils;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.database.data.DataTypeUtilities;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.lang.CompilerSpec;
import ghidra.program.model.listing.*;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.*;
import ghidra.program.model.symbol.*;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramMemoryUtil;
import ghidra.util.InvalidNameException;
import ghidra.util.Msg;
import ghidra.util.bytesearch.*;
import ghidra.util.datastruct.ListAccumulator;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;

public class RecoveredClassHelper {

	public static final String DTM_CLASS_DATA_FOLDER_NAME = "ClassDataTypes";
	public static final String DTM_CLASS_DATA_FOLDER_PATH = "/" + DTM_CLASS_DATA_FOLDER_NAME + "/";
	private static final String CLASS_DATA_STRUCT_NAME = "_data";
	private static final String DEFAULT_VFUNCTION_PREFIX = "vfunction";
	private static final String VFUNCTION_COMMENT = "virtual function #";
	private static final String CLASS_VFUNCTION_STRUCT_NAME = "_vftable";
	private static final String CLASS_VTABLE_PTR_FIELD_EXT = "vftablePtr";

	public static final String VFTABLE_LABEL = "vftable";
	private static final String VBASE_DESTRUCTOR_LABEL = "vbase_destructor";
	private static final String VBTABLE_LABEL = "vbtable";
	private static final String VBTABLE_PTR = "vbtablePtr";
	private static final String CLONE_LABEL = "clone";
	private static final String DELETING_DESTRUCTOR_LABEL = "deleting_destructor";

	private static final String BOOKMARK_CATEGORY = "RECOVERED CLASS";

	private static final String INLINE_CONSTRUCTOR_BOOKMARK = "INLINED CONSTRUCTOR";
	private static final String INLINE_DESTRUCTOR_BOOKMARK = "INLINED DESTRUCTOR";

	private static final String INDETERMINATE_INLINE_BOOKMARK = "INDETERMINATE INLINE";

	private static final int NONE = -1;

	private static final boolean DEBUG = false;

	private Map<Address, RecoveredClass> vftableToClassMap = new HashMap<Address, RecoveredClass>();

	// map from vftable references to the vftables they point to
	private Map<Address, Address> vftableRefToVftableMap = new HashMap<Address, Address>();

	// map from function to its vftable references
	private Map<Function, List<Address>> functionToVftableRefsMap =
		new HashMap<Function, List<Address>>();

	// map from function to class(es) it is in
	private Map<Function, List<RecoveredClass>> functionToClassesMap =
		new HashMap<Function, List<RecoveredClass>>();

	// map from function to all called constructors/destructors ref/addr pairs
	Map<Function, List<ReferenceAddressPair>> functionToCalledConsDestRefAddrPairMap =
		new HashMap<Function, List<ReferenceAddressPair>>();

	// map from class to list of possible parent classes
	Map<RecoveredClass, List<RecoveredClass>> possibleParentMap =
		new HashMap<RecoveredClass, List<RecoveredClass>>();

	// map from namespace to class object
	Map<Namespace, RecoveredClass> namespaceToClassMap = new HashMap<Namespace, RecoveredClass>();

	Map<Function, List<OffsetPcodeOpPair>> functionToStorePcodeOps =
		new HashMap<Function, List<OffsetPcodeOpPair>>();
	Map<Function, List<OffsetPcodeOpPair>> functionToLoadPcodeOps =
		new HashMap<Function, List<OffsetPcodeOpPair>>();

	List<Function> allClassFunctionsContainingVftableReference = new ArrayList<Function>();
	Set<Function> allPossibleConstructorsAndDestructors = new HashSet<Function>();
	Set<Function> allVfunctions = new HashSet<Function>();
	List<Function> allConstructors = new ArrayList<Function>();
	List<Function> allDestructors = new ArrayList<Function>();
	List<Function> allInlinedConstructors = new ArrayList<Function>();
	List<Function> allInlinedDestructors = new ArrayList<Function>();
	List<Function> nonClassInlines = new ArrayList<Function>();

	Set<Namespace> badFIDNamespaces = new HashSet<Namespace>();
	List<Structure> badFIDStructures = new ArrayList<Structure>();

	List<Function> badFIDFunctions = new ArrayList<Function>();
	List<Function> resolvedFIDFunctions = new ArrayList<Function>();
	List<Function> fixedFIDFunctions = new ArrayList<Function>();

	List<Function> operatorNewFunctions = new ArrayList<Function>();
	private static Function purecall = null;

	private static Function atexit = null;
	List<Function> atexitCalledFunctions = new ArrayList<Function>();

	GlobalNamespace globalNamespace;
	DataTypeManager dataTypeManager;
	int defaultPointerSize;
	SymbolTable symbolTable;

	ExtendedFlatProgramAPI extendedFlatAPI;

	DecompilerScriptUtils decompilerUtils;
	CategoryPath classDataTypesCategoryPath;

	private TaskMonitor monitor;
	private Program program;
	ProgramLocation location;
	PluginTool tool;
	FlatProgramAPI api;
	boolean createBookmarks;
	boolean useShortTemplates;
	boolean nameVfunctions;

	public RecoveredClassHelper(Program program, ProgramLocation location, PluginTool tool,
			FlatProgramAPI api, boolean createBookmarks, boolean useShortTemplates,
			boolean nameVunctions, TaskMonitor monitor)
			throws Exception {

		this.monitor = monitor;
		this.program = program;
		this.location = location;
		this.tool = tool;
		this.api = api;

		extendedFlatAPI = new ExtendedFlatProgramAPI(program, monitor);

		CategoryPath path = new CategoryPath(CategoryPath.ROOT, DTM_CLASS_DATA_FOLDER_NAME);

		this.classDataTypesCategoryPath = path;
		this.createBookmarks = createBookmarks;
		this.useShortTemplates = useShortTemplates;
		this.nameVfunctions = nameVunctions;

		globalNamespace = (GlobalNamespace) program.getGlobalNamespace();

		decompilerUtils = new DecompilerScriptUtils(program, tool, monitor);

		dataTypeManager = program.getDataTypeManager();
		symbolTable = program.getSymbolTable();

		defaultPointerSize = program.getDefaultPointerSize();
	}

	public void updateVftableToClassMap(Address vftableAddress, RecoveredClass recoveredClass) {
		if (vftableToClassMap.get(vftableAddress) == null) {
			vftableToClassMap.put(vftableAddress, recoveredClass);
		}
	}

	public RecoveredClass getVftableClass(Address vftableAddress) {
		return vftableToClassMap.get(vftableAddress);
	}

	/**
	 * Method to create a mappings between the list of vftable references to the vftables they reference
	 * @param referencesToVftable addresses that reference the given vftable address
	 * @param vftableAddress the given vftable address
	 * @throws CancelledException if cancelled
	 */
	public void addReferenceToVtableMapping(List<Address> referencesToVftable,
			Address vftableAddress) throws CancelledException {

		Iterator<Address> referencesIterator = referencesToVftable.iterator();
		while (referencesIterator.hasNext()) {
			Address vtableReference = referencesIterator.next();
			monitor.checkCanceled();
			vftableRefToVftableMap.put(vtableReference, vftableAddress);
		}
	}

	public Address getVftableAddress(Address vftableReference) {
		return vftableRefToVftableMap.get(vftableReference);
	}

	/**
	 * Method to update the functionToVftableListMap with the given input
	 * @param vftableRefToFunctionMapping a mapping of a vftable reference to the function it is in
	 * @throws CancelledException if cancelled
	 */
	public void addFunctionToVftableReferencesMapping(
			Map<Address, Function> vftableRefToFunctionMapping) throws CancelledException {

		Set<Address> keySet = vftableRefToFunctionMapping.keySet();
		Iterator<Address> referencesIterator = keySet.iterator();
		while (referencesIterator.hasNext()) {
			monitor.checkCanceled();
			Address vtableReference = referencesIterator.next();
			Function function = vftableRefToFunctionMapping.get(vtableReference);
			if (functionToVftableRefsMap.containsKey(function)) {
				List<Address> referenceList = functionToVftableRefsMap.get(function);
				if (!referenceList.contains(vtableReference)) {
					List<Address> newReferenceList = new ArrayList<Address>(referenceList);
					newReferenceList.add(vtableReference);
					functionToVftableRefsMap.replace(function, referenceList, newReferenceList);
				}
			}
			else {
				List<Address> referenceList = new ArrayList<Address>();
				referenceList.add(vtableReference);
				functionToVftableRefsMap.put(function, referenceList);
			}
		}
	}

	public List<Address> getVftableReferences(Function function) {
		return functionToVftableRefsMap.get(function);
	}

	/**
	 * Method to add function to map of class it is contained in some functions are in
	 * multiple classes becuase they have references to multiple class vtables either
	 * because they have an inlined parent
	 * @param functions the given list of functions
	 * @param recoveredClass the given class
	 * @throws CancelledException if cancelled
	 */
	public void addFunctionsToClassMapping(List<Function> functions, RecoveredClass recoveredClass)
			throws CancelledException {

		Iterator<Function> functionIterator = functions.iterator();
		while (functionIterator.hasNext()) {
			monitor.checkCanceled();
			Function function = functionIterator.next();
			// if the map already contains a mapping for function and if
			// the associated class list doesn't contain the new class, then
			// add the new class and update the mapping
			if (functionToClassesMap.containsKey(function)) {
				List<RecoveredClass> classList = functionToClassesMap.get(function);
				if (!classList.contains(recoveredClass)) {
					List<RecoveredClass> newClassList = new ArrayList<RecoveredClass>(classList);
					newClassList.add(recoveredClass);
					functionToClassesMap.replace(function, classList, newClassList);
				}
			}
			// if the map doesn't contain a mapping for function, then add it
			else {
				List<RecoveredClass> classList = new ArrayList<RecoveredClass>();
				classList.add(recoveredClass);
				functionToClassesMap.put(function, classList);
			}
		}

	}

	public List<RecoveredClass> getClasses(Function function) {
		return functionToClassesMap.get(function);
	}

	/**
	 * Method to find all the vftables in the program
	 * @return list of all vftable symbols
	 * @throws CancelledException when cancelled
	 */
	public List<Symbol> getListOfVftableSymbols() throws CancelledException {

		// Do with *'s to also get the PDB ones
		SymbolIterator vftableSymbols =
			program.getSymbolTable().getSymbolIterator("*vftable*", true);

		List<Symbol> vftableSymbolList = new ArrayList<Symbol>();
		while (vftableSymbols.hasNext()) {
			monitor.checkCanceled();
			Symbol vftableSymbol = vftableSymbols.next();
			if (vftableSymbol.getName().equals("vftable")) {
				vftableSymbolList.add(vftableSymbol);
			}
			// check for ones that are pdb that start with ' and may or may not end with '
			// can't just get all that contain vftable because that would get some strings
			else {
				String name = vftableSymbol.getName();
				name = name.substring(1, name.length());
				if (name.startsWith("vftable")) {
					vftableSymbolList.add(vftableSymbol);
				}
			}
		}
		return vftableSymbolList;
	}

	/**
	 * Method to return a symbol with the given name in the given namespace which is in the given
	 * parent namespace or null if one is not found
	 * @param parentNamespaceName name of parent namespace
	 * @param namespaceName name of symbol namespace
	 * @param symbolName name of symbol
	 * @return Symbol with given name, namespace and parent namespace or null if doesn't exist
	 * @throws CancelledException if cancelled
	 */
	public Symbol getSymbolInNamespaces(String parentNamespaceName, String namespaceName,
			String symbolName) throws CancelledException {

		SymbolIterator symbols = program.getSymbolTable().getSymbols(symbolName);
		while (symbols.hasNext()) {
			monitor.checkCanceled();
			Symbol symbol = symbols.next();
			if (symbol.getParentNamespace().getName().equals(namespaceName)) {
				Namespace namespace = symbol.getParentNamespace();
				if (namespace.getParentNamespace().getName().equals(parentNamespaceName)) {
					return symbol;
				}
			}
		}
		return null;
	}

	public Address getSingleAddressOfSymbolContainingBothStrings(String string1, String string2)
			throws CancelledException {

		List<Address> symbolAddressList = new ArrayList<Address>();

		SymbolIterator symbols =
			program.getSymbolTable().getSymbolIterator("*" + string1 + "*", true);

		while (symbols.hasNext()) {
			monitor.checkCanceled();
			Symbol symbol = symbols.next();
			Address symbolAddress = symbol.getAddress();

			if (symbol.getName().contains(string2)) {
				if (!symbolAddressList.contains(symbolAddress)) {
					symbolAddressList.add(symbolAddress);
				}
			}
		}
		if (symbolAddressList.size() == 1) {
			return symbolAddressList.get(0);
		}
		return null;
	}

	/**
	 * Method to create a map with keyset containing all constructor/destructor functions in the
	 * given list of classes and associated mapping to list of refAddrPairs for called
	 * constructor/destructor functions
	 * @param recoveredClasses the list of classes
	 * @throws CancelledException if cancelled
	 */
	public void createCalledFunctionMap(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();
			List<Function> constructorOrDestructorFunctions =
				recoveredClass.getConstructorOrDestructorFunctions();
			Iterator<Function> functionIterator = constructorOrDestructorFunctions.iterator();
			while (functionIterator.hasNext()) {
				monitor.checkCanceled();
				Function function = functionIterator.next();
				createFunctionToCalledConstructorOrDestructorRefAddrPairMapping(function);
			}
		}

	}

	/**
	 * Method to determine and add the mapping of the given function to its called constructor or destructor functions
	 * @param function given function
	 * @throws CancelledException when script is canceled
	 */
	public void createFunctionToCalledConstructorOrDestructorRefAddrPairMapping(Function function)
			throws CancelledException {

		List<ReferenceAddressPair> referenceAddressPairs = new ArrayList<ReferenceAddressPair>();
		Set<Function> calledFunctions = function.getCalledFunctions(monitor);
		Iterator<Function> calledFunctionIterator = calledFunctions.iterator();
		while (calledFunctionIterator.hasNext()) {
			monitor.checkCanceled();
			Function calledFunction = calledFunctionIterator.next();
			Function referencedFunction = calledFunction;
			if (calledFunction.isThunk()) {
				Function thunkFunction = calledFunction.getThunkedFunction(true);
				calledFunction = thunkFunction;
			}
			// if thunk, need to use the thunked function to see if it is on list of cds
			// but always need to use the actual called function to get reference address
			// need to used the thunked function on the hashmap
			if (allClassFunctionsContainingVftableReference.contains(calledFunction)) {
				// get list of refs to this function from the calling function
				List<Address> referencesToFunctionBFromFunctionA = extendedFlatAPI
						.getReferencesToFunctionBFromFunctionA(function, referencedFunction);
				// add them to list of ref address pairs
				Iterator<Address> iterator = referencesToFunctionBFromFunctionA.iterator();
				while (iterator.hasNext()) {
					monitor.checkCanceled();
					Address sourceRefAddr = iterator.next();
					referenceAddressPairs.add(
						new ReferenceAddressPair(sourceRefAddr, calledFunction.getEntryPoint()));
				}
			}
		}
		// add list to global map
		functionToCalledConsDestRefAddrPairMap.put(function, referenceAddressPairs);

	}

	/**
	 * Method to return a map of address to function calls for the given function
	 * @param function given function
	 * @return map of address to function calls for the given function or null if no called functions
	 * @throws CancelledException when script is canceled
	 */
	public Map<Address, Function> getAddressToFunctionCallMap(Function function)
			throws CancelledException {

		Map<Address, Function> addressToFunctionCallMap = new HashMap<Address, Function>();

		Set<Function> calledFunctions = function.getCalledFunctions(monitor);

		if (calledFunctions.isEmpty()) {
			return null;
		}

		for (Function calledFunction : calledFunctions) {
			monitor.checkCanceled();

			Function referencedFunction = calledFunction;

			// get list of refs to this function from the calling function
			List<Address> referencesToFunctionBFromFunctionA =
				extendedFlatAPI.getReferencesToFunctionBFromFunctionA(function, referencedFunction);

			// add them to list of ref address pairs
			for (Address sourceRefAddr : referencesToFunctionBFromFunctionA) {
				monitor.checkCanceled();
				addressToFunctionCallMap.put(sourceRefAddr, calledFunction);
			}
		}
		return addressToFunctionCallMap;

	}


	public List<ReferenceAddressPair> getCalledConstDestRefAddrPairs(Function function) {
		return functionToCalledConsDestRefAddrPairMap.get(function);
	}

	public void updateNamespaceToClassMap(Namespace namespace, RecoveredClass recoveredClass) {
		namespaceToClassMap.put(namespace, recoveredClass);
	}

	public RecoveredClass getClass(Namespace namespace) {
		return namespaceToClassMap.get(namespace);
	}

	public void updateFunctionToStorePcodeOpsMap(Function function,
			List<OffsetPcodeOpPair> offsetPcodeOpPairs) {
		if (functionToStorePcodeOps.get(function) == null) {
			functionToStorePcodeOps.put(function, offsetPcodeOpPairs);
		}
	}

	public List<OffsetPcodeOpPair> getStorePcodeOpPairs(Function function) {
		return functionToStorePcodeOps.get(function);
	}

	public void updateFunctionToLoadPcodeOpsMap(Function function,
			List<OffsetPcodeOpPair> offsetPcodeOpPairs) {
		if (functionToLoadPcodeOps.get(function) == null) {
			functionToLoadPcodeOps.put(function, offsetPcodeOpPairs);
		}
	}

	public List<OffsetPcodeOpPair> getLoadPcodeOpPairs(Function function) {
		return functionToLoadPcodeOps.get(function);
	}

	public void updateAllVfunctionsSet(List<Function> vfunctions) {
		allVfunctions.addAll(vfunctions);
	}

	public Set<Function> getAllVfunctions() {
		return allVfunctions;
	}

	public void updateAllClassFunctionsWithVftableRefList(List<Function> functions) {
		allClassFunctionsContainingVftableReference.addAll(functions);
	}

	public List<Function> getAllClassFunctionsWithVtableRef() {
		return allClassFunctionsContainingVftableReference;
	}

	public void updateAllPossibleConstructorDestructorList(Function function) {
		allPossibleConstructorsAndDestructors.add(function);
	}

	public Set<Function> getAllPossibleConstructorsAndDestructors() {
		return allPossibleConstructorsAndDestructors;
	}

	public void addToAllConstructors(Function function) {
		allConstructors.add(function);
	}

	public void removeFromAllConstructors(Function function) {
		allConstructors.remove(function);
	}

	public List<Function> getAllConstructors() {
		return allConstructors;
	}

	public void addToAllDestructors(Function function) {
		allDestructors.add(function);
	}

	public void removeFromAllDestructors(Function function) {
		allDestructors.remove(function);
	}

	public List<Function> getAllDestructors() {
		return allDestructors;
	}

	public void addToAllInlinedConstructors(Function function) {
		allInlinedConstructors.add(function);
	}

	public void removeFromAllInlinedConstructors(Function function) {
		allInlinedConstructors.remove(function);
	}

	public List<Function> getAllInlinedConstructors() {
		return allInlinedConstructors;
	}

	public void addToAllInlinedDestructors(Function function) {
		allInlinedDestructors.add(function);
	}

	public void removeFromAllInlinedDestructors(Function function) {
		allInlinedDestructors.remove(function);
	}

	public List<Function> getAllInlinedDestructors() {
		return allInlinedDestructors;
	}

	/**
	 * Method to determine if referenced vftables are from the same class
	 * @param vftableReferences list of vftable references
	 * @return true if all listed vftable references refer to vftables from the same class, false otherwise
	 * @throws CancelledException when cancelled
	 */
	public boolean areVftablesInSameClass(List<Address> vftableReferences)
			throws CancelledException {

		List<RecoveredClass> classes = new ArrayList<RecoveredClass>();

		Iterator<Address> iterator = vftableReferences.iterator();
		while (iterator.hasNext()) {
			monitor.checkCanceled();
			Address vftableReference = iterator.next();
			Address vftableAddress = vftableRefToVftableMap.get(vftableReference);
			RecoveredClass recoveredClass = vftableToClassMap.get(vftableAddress);
			if (!classes.contains(recoveredClass)) {
				classes.add(recoveredClass);
			}
		}
		if (classes.size() > 1) {
			return false;
		}
		return true;
	}

	/**
	 * Method to return the first reference to the class vftable in the given function
	 * @param recoveredClass the given class
	 * @param function the given function
	 * @return the reference to the class vftable in the given function or null if there isn't one
	 * @throws CancelledException if cancelled
	 */
	public Address getFirstClassVftableReference(RecoveredClass recoveredClass, Function function)
			throws CancelledException {

		List<Address> vftableReferenceList = functionToVftableRefsMap.get(function);

		if (vftableReferenceList == null) {
			return null;
		}

		Collections.sort(vftableReferenceList);

		Iterator<Address> vftableRefs = vftableReferenceList.iterator();
		while (vftableRefs.hasNext()) {
			monitor.checkCanceled();
			Address vftableRef = vftableRefs.next();

			Address vftableAddress = vftableRefToVftableMap.get(vftableRef);
			if (vftableAddress == null) {
				continue;
			}

			RecoveredClass referencedClass = vftableToClassMap.get(vftableAddress);
			if (referencedClass == null) {
				continue;
			}

			if (referencedClass.equals(recoveredClass)) {
				return vftableRef;
			}
		}
		return null;

	}

	/**
	 * Method to get a sorted list of both vftable and call refs to ancestor classes of the given
	 * class in the given function
	 * @param function the given function
	 * @param recoveredClass the given class
	 * @return a sorted list of both vftable and call refs to ancestor classes of the given
	 * class in the given function
	 * @throws CancelledException if cancelled
	 */
	public List<Address> getSortedListOfAncestorRefsInFunction(Function function,
			RecoveredClass recoveredClass) throws CancelledException {

		// get the map of all referenced vftables or constructor/desstructor calls in this function
		Map<Address, RecoveredClass> referenceToClassMapForFunction =
			getReferenceToClassMap(recoveredClass, function);

		// get a list of all ancestor classes referenced in the map
		List<RecoveredClass> classHierarchy = recoveredClass.getClassHierarchy();

		// make a list of all related class references
		List<Address> listOfAncestorRefs = new ArrayList<Address>();
		Set<Address> ancestorRefs = referenceToClassMapForFunction.keySet();
		Iterator<Address> ancestorRefIterator = ancestorRefs.iterator();
		while (ancestorRefIterator.hasNext()) {
			monitor.checkCanceled();
			Address ancestorRef = ancestorRefIterator.next();
			RecoveredClass mappedClass = referenceToClassMapForFunction.get(ancestorRef);
			if (classHierarchy.contains(mappedClass)) {
				listOfAncestorRefs.add(ancestorRef);
			}
		}

		Collections.sort(listOfAncestorRefs);
		return listOfAncestorRefs;
	}

	/**
	 * Method to create a map of all references to classes in the given function. Classes are, for this purpose, referenced if they
	 * a vftable belonging to a class is referenced or if a constructor/destructor function from a class is called
	 * @param recoveredClass the given class
	 * @param function the given function
	 * @return Map of Address references to Class object for the given function
	 * @throws CancelledException when cancelled
	 */
	public Map<Address, RecoveredClass> getReferenceToClassMap(RecoveredClass recoveredClass,
			Function function) throws CancelledException {

		Map<Address, RecoveredClass> referenceToParentMap = new HashMap<Address, RecoveredClass>();

		List<Address> vftableRefs = functionToVftableRefsMap.get(function);

		if (vftableRefs == null) {
			return referenceToParentMap;
		}

		// iterate through all vftable refs in the function and add it to ref/Parent map
		Iterator<Address> vftableRefIterator = vftableRefs.iterator();
		while (vftableRefIterator.hasNext()) {

			monitor.checkCanceled();

			Address vftableRef = vftableRefIterator.next();
			Address vftableAddress = extendedFlatAPI.getSingleReferencedAddress(vftableRef);

			if (vftableAddress == null) {
				continue;
			}

			RecoveredClass parentClass = vftableToClassMap.get(vftableAddress);
			referenceToParentMap.put(vftableRef, parentClass);
		}

		// remove duplicate vftable refs (occasionally there are LEA then MOV of same vftable address
		// a few intructions of each other. It confuses later processes to have both.
		referenceToParentMap = dedupeMap(referenceToParentMap);

		// iterate through all the ref/addr pairs of called constructors/destructors in the function
		// and get the class the constructor/destructor belongs to and add the ref/Parent pair to the
		// same map as the vftable refs above
		List<ReferenceAddressPair> refAddrPairsToCalledParents =
			functionToCalledConsDestRefAddrPairMap.get(function);

		// if no calls then just return the ones found in the vftable section above
		if (refAddrPairsToCalledParents == null) {
			return referenceToParentMap;
		}

		Iterator<ReferenceAddressPair> refAddPairIterator = refAddrPairsToCalledParents.iterator();

		while (refAddPairIterator.hasNext()) {

			monitor.checkCanceled();

			ReferenceAddressPair parentRefAddrPair = refAddPairIterator.next();

			Address parentConstructorAddress = parentRefAddrPair.getDestination();
			Function parentConstructor =
				program.getFunctionManager().getFunctionAt(parentConstructorAddress);

			if (parentConstructor.isThunk()) {
				parentConstructor = parentConstructor.getThunkedFunction(true);
			}

			RecoveredClass ancestorClass =
				getAncestorClassWithGivenFunction(recoveredClass, parentConstructor);

			if (ancestorClass == null) {
				continue;
			}

			referenceToParentMap.put(parentRefAddrPair.getSource(), ancestorClass);
		}

		return referenceToParentMap;
	}

	/**
	 * Get the list of incorrect FID functions
	 * @return the list of incorrect FID functions
	 */
	public List<Function> getBadFIDFunctions() {
		return badFIDFunctions;
	}

	/**
	 * Get the list of resolved functions that had multiple FID possibilities before but could
	 * be resolved to the correct name.
	 * @return the list of resolved FID functions
	 */
	public List<Function> getResolvedFIDFunctions() {
		return badFIDFunctions;
	}

	/**
	 * Get the fixed functions that had incorrect data types due to incorrect FID (includes those
	 * incorrect due to decompiler propagation of bad types from bad FID)
	 * @return the fixed functions that had incorrect data types due to incorrect FID
	 */
	public List<Function> getFixedFIDFunctions() {
		return badFIDFunctions;
	}

	private Map<Address, RecoveredClass> dedupeMap(Map<Address, RecoveredClass> map)
			throws CancelledException {

		// Sort the vftable refs in the order they appear in the function
		Set<Address> vftableRefs = map.keySet();
		List<Address> vftableRefList = new ArrayList<Address>(vftableRefs);
		Collections.sort(vftableRefList);

		Iterator<Address> vftableRefIterator = vftableRefList.iterator();
		RecoveredClass lastClass = null;
		Address lastVftableRef = null;
		while (vftableRefIterator.hasNext()) {
			monitor.checkCanceled();
			Address vftableRef = vftableRefIterator.next();
			RecoveredClass currentClass = map.get(vftableRef);

			if (lastClass != null && lastClass.equals(currentClass)) {
				// if vftable refs are within a few instructions, dedupe map
				if (numInstructionsApart(lastVftableRef, vftableRef) <= 3) {
					map.remove(vftableRef);
				}
			}

			lastClass = currentClass;
			lastVftableRef = vftableRef;
		}

		return map;
	}

	private int numInstructionsApart(Address ref1, Address ref2) throws CancelledException {

		Instruction instruction = program.getListing().getInstructionAfter(ref1);
		int numApart = 0;
		while (instruction != null && !instruction.contains(ref2)) {
			monitor.checkCanceled();
			numApart++;
			instruction = program.getListing().getInstructionAfter(instruction.getMaxAddress());
		}
		return numApart;
	}

	/**
	 * Method to figure out which of the multiple parents of a class contain the given function in their class
	 * @param recoveredClass the given class with multiple parents
	 * @param function the given function that is in one of the parent classes
	 * @return the parent class that the given function belongs to
	 * @throws CancelledException if cancelled
	 */
	private RecoveredClass getAncestorClassWithGivenFunction(RecoveredClass recoveredClass,
			Function function) throws CancelledException {

		List<RecoveredClass> classList = functionToClassesMap.get(function);

		if (classList == null) {
			return null;
		}

		if (classList.contains(recoveredClass)) {
			classList.remove(recoveredClass);
		}

		if (classList.size() == 0) {
			return null;
		}

		if (classList.size() == 1) {
			return classList.get(0);
		}

		List<RecoveredClass> parentClasses =
			new ArrayList<RecoveredClass>(recoveredClass.getClassHierarchyMap().keySet());

		// try direct parents first
		Iterator<RecoveredClass> parentsIterator = parentClasses.iterator();
		while (parentsIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass parentClass = parentsIterator.next();
			List<Function> constructorDestructorList =
				new ArrayList<Function>(parentClass.getConstructorList());
			constructorDestructorList.addAll(parentClass.getDestructorList());
			if (constructorDestructorList.contains(function)) {
				return parentClass;
			}
		}

		// if not found in direct parents, try all ancestors
		List<RecoveredClass> ancestorClasses = recoveredClass.getClassHierarchy();

		if (ancestorClasses.size() <= 1) {
			return recoveredClass;
		}

		ListIterator<RecoveredClass> ancestorIterator = ancestorClasses.listIterator(1);
		while (ancestorIterator.hasNext()) {
			monitor.checkCanceled();

			RecoveredClass ancestorClass = ancestorIterator.next();

			// already checked the parents
			if (parentClasses.contains(ancestorClass)) {
				continue;
			}
			List<Function> constructorDestructorList =
				new ArrayList<Function>(ancestorClass.getConstructorList());
			constructorDestructorList.addAll(ancestorClass.getDestructorList());
			if (constructorDestructorList.contains(function)) {
				return ancestorClass;
			}
		}

		return null;
	}

	/**
	 * Method to get a list of addresses that are references from the given address
	 * @param address the given address
	 * @return a list of addresses that are references from the given address
	 */
	private List<Address> getReferenceFromAddresses(Address address) {

		Reference[] referencesFrom = program.getReferenceManager().getReferencesFrom(address);

		// get only the address references at the given address (ie no stack refs, ...)
		List<Address> refFromAddresses = new ArrayList<Address>();
		for (Reference referenceFrom : referencesFrom) {
			if (referenceFrom.isMemoryReference()) {
				refFromAddresses.add(referenceFrom.getToAddress());
			}
		}

		return refFromAddresses;
	}

	/**
	 * Retrieve the first stored vftable from the pcodeOps in the list
	 * @param storedPcodeOps list of offset/PcodeOp pairs
	 * @return first referenced vftable address
	 * @throws CancelledException if cancelled
	 */
	public Address getStoredVftableAddress(List<OffsetPcodeOpPair> storedPcodeOps)
			throws CancelledException {

		if (storedPcodeOps.size() > 0) {
			Iterator<OffsetPcodeOpPair> iterator = storedPcodeOps.iterator();
			// figure out if vftable is referenced
			while (iterator.hasNext()) {
				monitor.checkCanceled();
				OffsetPcodeOpPair offsetPcodeOpPair = iterator.next();
				PcodeOp pcodeOp = offsetPcodeOpPair.getPcodeOp();
				Varnode storedValue = pcodeOp.getInput(2);
				Address vftableAddress = decompilerUtils.getAssignedAddressFromPcode(storedValue);
				if (vftableAddress != null && vftableToClassMap.containsKey(vftableAddress)) {
					return vftableAddress;
				}
			}
		}
		return null;
	}

	/**
	 * Method to get a list of addresses that reference the given vftable address
	 * @param vftableAddress the given vftable address
	 * @return list of addresses that reference the given vftable address
	 * @throws CancelledException if cancelled
	 */
	public List<Address> getReferencesToVftable(Address vftableAddress) throws CancelledException {

		List<Address> referencesToVftable = new ArrayList<>();

		ReferenceIterator iterator = program.getReferenceManager().getReferencesTo(vftableAddress);

		while (iterator.hasNext()) {

			monitor.checkCanceled();

			Reference reference = iterator.next();
			Address vtableReference = reference.getFromAddress();
			referencesToVftable.add(vtableReference);
		}

		return referencesToVftable;
	}

	/**
	 * Method to determine if the given function is an inlined destructor or indeterminate in any class
	 * @param function the given function
	 * @return true if the given function is an inlined function in any class, false if it is not an
	 * inlined function in any class
	 * @throws CancelledException if cancelled
	 */
	public boolean isInlineDestructorOrIndeterminateInAnyClass(Function function)
			throws CancelledException {

		List<RecoveredClass> functionClasses = getClasses(function);
		if (functionClasses == null) {
			if (DEBUG) {
				Msg.debug(this, "no function to class map for " + function.getEntryPoint());
			}
			return true;
		}
		Iterator<RecoveredClass> functionClassesIterator = functionClasses.iterator();
		while (functionClassesIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = functionClassesIterator.next();

			if (recoveredClass.getInlinedDestructorList().contains(function)) {
				return true;
			}
			if (recoveredClass.getIndeterminateInlineList().contains(function)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method to create mappings for the current class and use the decompiler
	 * to figure out class member data information
	 * @param recoveredClass class that function belongs to
	 * @param function current function to process
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if issues setting return type
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	public void gatherClassMemberDataInfoForFunction(RecoveredClass recoveredClass,
			Function function) throws CancelledException, DuplicateNameException,
			InvalidInputException, CircularDependencyException {

		// save off param and return information
		Parameter[] originalParameters = function.getParameters();
		DataType[] originalTypes = new DataType[originalParameters.length];
		SourceType[] originalSources = new SourceType[originalParameters.length];
		for (int i = 0; i < originalParameters.length; i++) {
			monitor.checkCanceled();
			originalTypes[i] = originalParameters[i].getDataType();
			originalSources[i] = originalParameters[i].getSource();
		}
		DataType originalReturnType = function.getReturnType();
		SourceType originalReturnSource = function.getReturn().getSource();

		Namespace originalNamespace = function.getParentNamespace();

		// temporarily remove class data type or other empty structures from return and params
		// so they do not skew the structure contents from FillOutStructureCmd
		temporarilyReplaceEmptyStructures(function, recoveredClass.getClassNamespace());

		// create maps and updates class member data information using structure and pcode info  returned
		// from FillOutStructureCmd
		updateMapsAndClassMemberDataInfo(function, recoveredClass);

		// replace namespace if it was removed
		if (!function.getParentNamespace().equals(originalNamespace)) {
			function.setParentNamespace(originalNamespace);
		}

		// put back return and param data types that were temporarily removed
		Parameter[] parameters = function.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			monitor.checkCanceled();
			if (parameters[i].getName().equals("this")) {
				continue;
			}
			if (!parameters[i].getDataType().equals(originalTypes[i])) {
				parameters[i].setDataType(originalTypes[i], originalSources[i]);
			}
		}
		if (!function.getReturnType().equals(originalReturnType)) {
			function.setReturnType(originalReturnType, originalReturnSource);
		}
	}

	/**
	 * Method to update the given class's maps and class member data using the given function info
	 * @param function the given function
	 * @param recoveredClass the given class
	 * @throws CancelledException if cancelled
	 */
	private void updateMapsAndClassMemberDataInfo(Function function, RecoveredClass recoveredClass)
			throws CancelledException {

		List<Address> vftableReferenceList = getVftableReferences(function);
		if (vftableReferenceList == null) {
			if (DEBUG) {
				Msg.debug(this, "In update maps: function to class map doesn't exist for " +
					function.getEntryPoint().toString());
			}
			return;
		}
		Collections.sort(vftableReferenceList);
		Address firstVftableReference = vftableReferenceList.get(0);
		if (firstVftableReference == null) {
			return;
		}

		// make sure first vftable ref is in the given class (if inlined it might not be)
		Address vftableAddress = getVftableAddress(firstVftableReference);
		RecoveredClass vftableClass = getVftableClass(vftableAddress);
		if (!vftableClass.equals(recoveredClass)) {

			if (DEBUG) {
				Msg.debug(this,
					"updating struct for " + recoveredClass.getName() +
						" but first vftable in function " + function.getEntryPoint().toString() +
						" is in class " + vftableClass.getName());
			}

			return;
		}

		getStructureFromDecompilerPcode(recoveredClass, function, firstVftableReference);

	}

	/**
	 * Method to retrieve a filled-in structure associated with the given function's high variable
	 * that stores the given first vftable reference address in the given function.
	 * @param recoveredClass the given class
	 * @param function the given function
	 * @param firstVftableReference the first vftable reference address in the given function
	 * @throws CancelledException if cancelled
	 */
	public void getStructureFromDecompilerPcode(RecoveredClass recoveredClass, Function function,
			Address firstVftableReference) throws CancelledException {

		// get the decompiler highFunction
		HighFunction highFunction = decompilerUtils.getHighFunction(function);

		if (highFunction == null) {
			return;

		}

		List<HighVariable> highVariables = new ArrayList<HighVariable>();

		// if there are params add the first or the "this" param to the list to be checked first
		// It is the most likely to store the vftablePtr
		if (highFunction.getFunctionPrototype().getNumParams() > 0) {

			HighVariable thisParam =
				highFunction.getFunctionPrototype().getParam(0).getHighVariable();
			if (thisParam != null) {
				highVariables.add(thisParam);
			}
		}

		// add the other high variables that store vftable pointer
		highVariables
				.addAll(getVariableThatStoresVftablePointer(highFunction, firstVftableReference));
		Iterator<HighVariable> highVariableIterator = highVariables.iterator();

		Address vftableAddress = null;
		while (highVariableIterator.hasNext()) {

			HighVariable highVariable = highVariableIterator.next();
			monitor.checkCanceled();

			FillOutStructureCmd fillCmd = new FillOutStructureCmd(program, location, tool);

			Structure structure = fillCmd.processStructure(highVariable, function);

			NoisyStructureBuilder componentMap = fillCmd.getComponentMap();

			List<OffsetPcodeOpPair> stores = fillCmd.getStorePcodeOps();
			stores = removePcodeOpsNotInFunction(function, stores);

			// this method checks the storedPcodeOps to see if one is the desired vftable address
			vftableAddress = getStoredVftableAddress(stores);
			if (vftableAddress != null &&
				getVftableAddress(firstVftableReference).equals(vftableAddress)) {

				if (structure != null) {
					recoveredClass.updateClassMemberStructure(structure);
					recoveredClass.updateClassMemberStructureUndefineds(componentMap);
				}

				List<OffsetPcodeOpPair> loads = fillCmd.getLoadPcodeOps();
				loads = removePcodeOpsNotInFunction(function, loads);

				//functionToStorePcodeOps.put(function, stores);
				updateFunctionToStorePcodeOpsMap(function, stores);
				updateFunctionToLoadPcodeOpsMap(function, loads);
				return;
			}

			if (DEBUG) {
				Msg.debug(this, "Could not find variable pointing to vftable in " +
					function.getEntryPoint().toString());
			}

		}

	}

	/**
	 * Method to determine which variable in the decompiler stores the vftable address
	 * @param highFunction the decompiler high function
	 * @param vftableReference the address that points to a vftable
	 * @return the list of variables in the given function that store the vftable address
	 * @throws CancelledException if cancelled
	 */
	//TODO: Possibly refactor to use the same methodology as getAssignedAddressFrompcode or getStoredVftableAddress
	private List<HighVariable> getVariableThatStoresVftablePointer(HighFunction highFunction,
			Address vftableReference) throws CancelledException {

		List<HighVariable> highVars = new ArrayList<HighVariable>();

		Iterator<PcodeOpAST> pcodeOps = highFunction.getPcodeOps();
		while (pcodeOps.hasNext()) {
			monitor.checkCanceled();
			PcodeOp pcodeOp = pcodeOps.next();
			if (pcodeOp.getOpcode() == PcodeOp.STORE) {

				Address address = getTargetAddressFromPcodeOp(pcodeOp);
				if (address.equals(vftableReference)) {

					Varnode[] inputs = pcodeOp.getInputs();
					for (Varnode input : inputs) {
						monitor.checkCanceled();
						if (input.getHigh() != null) {
							highVars.add(input.getHigh());
						}
					}
				}
			}
		}
		return highVars;

	}

	/**
	 * temporarily change the function signature of the given constructor or destructor to replace
	 * any empty structure with same size undefined datatype and to also remove the functin from
	 * its namespace to remove the empty structure from the this param. This is so that the
	 * class member data calculations are made without bad info
	 * @param function the given function
	 * @param classNamespace the given class namespace
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if issue making function thiscall
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	private void temporarilyReplaceEmptyStructures(Function function, Namespace classNamespace)
			throws CancelledException, DuplicateNameException, InvalidInputException,
			CircularDependencyException {

		int parameterCount = function.getParameterCount();
		for (int i = 0; i < parameterCount; i++) {
			monitor.checkCanceled();

			// if this call - temporarily put in global namespace to remove class structure
			// in order to get unbiased pcode store information
			if (function.getParameter(i).getName().equals("this")) {
				function.setParentNamespace(globalNamespace);
				continue;
			}

			DataType dataType = function.getParameter(i).getDataType();

			if (!extendedFlatAPI.isPointerToEmptyStructure(dataType)) {
				continue;
			}

			PointerDataType ptrUndefined =
				extendedFlatAPI.createPointerToUndefinedDataType(dataType);
			if (ptrUndefined != null) {
				function.getParameter(i).setDataType(ptrUndefined, SourceType.ANALYSIS);
			}

		}

		// Next check the return type to see if it is the empty structure
		DataType returnType = function.getReturnType();
		if (extendedFlatAPI.isPointerToEmptyStructure(returnType)) {
			PointerDataType ptrUndefined =
				extendedFlatAPI.createPointerToUndefinedDataType(returnType);
			if (ptrUndefined != null) {
				function.setReturnType(ptrUndefined, SourceType.ANALYSIS);
			}
		}

	}

	/**
	 * Method to determine if the given possible ancestor is an ancestor of any of the listed classes
	 * @param recoveredClasses List of classes
	 * @param possibleAncestor possible ancestor of one of the listed classes
	 * @return true if ancestor of one of the listed classes, false otherwise
	 * @throws Exception if one of the classes has empty class hierarchy
	 */
	public boolean isClassAnAncestorOfAnyOnList(List<RecoveredClass> recoveredClasses,
			RecoveredClass possibleAncestor) throws Exception {

		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			if (isClassAnAncestor(recoveredClass, possibleAncestor)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method to determine if a class is an ancestor of another class
	 * @param recoveredClass the class with possible ancestor
	 * @param possibleAncestorClass the class that might be ancestor of recoveredClass
	 * @return true if possibleAncestorClass is an ancestor of recoveredClass, false otherwise
	 * @throws Exception if class hierarchy is empty
	 */
	private boolean isClassAnAncestor(RecoveredClass recoveredClass,
			RecoveredClass possibleAncestorClass) throws Exception {

		List<RecoveredClass> classHierarchy =
			new ArrayList<RecoveredClass>(recoveredClass.getClassHierarchy());

		if (classHierarchy.isEmpty()) {
			throw new Exception(
				recoveredClass.getName() + " should not have an empty class hierarchy");
		}

		//remove self
		classHierarchy.remove(recoveredClass);

		if (classHierarchy.contains(possibleAncestorClass)) {
			return true;
		}
		return false;
	}

	/**
	 * Method to return subList of offset/pcodeOp pairs from the given list that are contained in the current function
	 * @param function The given function
	 * @param pcodeOps The list of pcodeOps to filter
	 * @return List of pcodeOps from given list that are contained in given function
	 * @throws CancelledException when cancelled
	 */
	public List<OffsetPcodeOpPair> removePcodeOpsNotInFunction(Function function,
			List<OffsetPcodeOpPair> pcodeOps) throws CancelledException {

		Iterator<OffsetPcodeOpPair> pcodeOpsIterator = pcodeOps.iterator();
		while (pcodeOpsIterator.hasNext()) {
			monitor.checkCanceled();
			OffsetPcodeOpPair offsetPcodeOpPair = pcodeOpsIterator.next();
			PcodeOp pcodeOp = offsetPcodeOpPair.getPcodeOp();
			Address pcodeOpAddress = pcodeOp.getSeqnum().getTarget();
			if (!function.getBody().contains(pcodeOpAddress)) {
				pcodeOpsIterator.remove();
			}
		}
		return pcodeOps;
	}

	/**
	 * Method to get the listing address that the given PcodeOp is associated with
	 * @param pcodeOp the given PcodeOp
	 * @return the address the given PcodeOp is associated with
	 */
	public Address getTargetAddressFromPcodeOp(PcodeOp pcodeOp) {
		return pcodeOp.getSeqnum().getTarget();
	}

	public Function getCalledFunctionFromCallPcodeOp(PcodeOp calledPcodeOp) {

		Function calledFunction = api.getFunctionAt(calledPcodeOp.getInput(0).getAddress());

		if (calledFunction == null) {
			return null;
		}

		return calledFunction;
	}

	/**
	 * method to update the two given classes with their related class hierarchy
	 * @param parentClass parent of childClass
	 * @param childClass child of parentClass
	 */
	public void updateClassWithParent(RecoveredClass parentClass, RecoveredClass childClass) {

		// return if they are already a known parent/child pair
		if (parentClass.getChildClasses().contains(childClass) &&
			childClass.getParentList().contains(parentClass)) {
			return;
		}

		childClass.addParent(parentClass);

		childClass.setHasParentClass(true);
		parentClass.setHasChildClass(true);
		parentClass.addChildClass(childClass);

		return;
	}

	/**
	 * Method to retrieve only the classes that have vfunctions from the given list of classes
	 * @param recoveredClasses the given list of classes
	 * @return a list of classes that have vfunctions
	 * @throws CancelledException if cancelled
	 */
	public List<RecoveredClass> getClassesWithVFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		List<RecoveredClass> classesWithVFunctions = new ArrayList<RecoveredClass>();

		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();
			if (recoveredClass.hasVftable()) {
				classesWithVFunctions.add(recoveredClass);
			}
		}

		return classesWithVFunctions;
	}

	/**
	 * Method that returns a list of all parents with virtual functions for the given class
	 * @param recoveredClass given class
	 * @return list of all parents with virtual functions for given class or empty list if none
	 * @throws CancelledException when cancelled
	 */
	public List<RecoveredClass> getParentsWithVirtualFunctions(RecoveredClass recoveredClass)
			throws CancelledException {

		List<RecoveredClass> parentsWithVFunctions = new ArrayList<RecoveredClass>();

		Map<RecoveredClass, List<RecoveredClass>> classHierarchyMap =
			recoveredClass.getClassHierarchyMap();

		// no parents so return empty list
		if (classHierarchyMap.isEmpty()) {
			return parentsWithVFunctions;
		}

		List<RecoveredClass> parentClassList =
			new ArrayList<RecoveredClass>(classHierarchyMap.keySet());

		Iterator<RecoveredClass> parentIterator = parentClassList.iterator();
		while (parentIterator.hasNext()) {

			monitor.checkCanceled();

			RecoveredClass parentClass = parentIterator.next();
			if (parentClass.hasVftable()) {
				parentsWithVFunctions.add(parentClass);
			}
		}

		return parentsWithVFunctions;
	}

	/**
	 * Method to get list of the given class's ancestors that have virtual functions or functions inherited virtual functions(ie a vftable)
	 * @param recoveredClass the given class
	 * @return list of the given class's ancestors that have virtual functions or functions inherited virtual functions(ie a vftable)
	 * @throws CancelledException if cancelled
	 */
	public List<RecoveredClass> getAncestorsWithVirtualFunctions(RecoveredClass recoveredClass)
			throws CancelledException {

		List<RecoveredClass> ancestorsWithVFunctions = new ArrayList<RecoveredClass>();

		// no parents so return empty list
		if (!recoveredClass.hasParentClass()) {
			return ancestorsWithVFunctions;
		}

		List<RecoveredClass> classHierarchyList = recoveredClass.getClassHierarchy();

		Iterator<RecoveredClass> ancestorIterator = classHierarchyList.iterator();
		while (ancestorIterator.hasNext()) {

			monitor.checkCanceled();

			RecoveredClass parentClass = ancestorIterator.next();
			if (parentClass.hasVftable()) {
				ancestorsWithVFunctions.add(parentClass);
			}
		}

		return ancestorsWithVFunctions;
	}

	/**
	 * Method to get ancestors that do not have vfunctions
	 * @param recoveredClass the given class
	 * @return List of ancestors without vfunctions
	 * @throws CancelledException if cancelled
	 */
	public List<RecoveredClass> getAncestorsWithoutVfunctions(RecoveredClass recoveredClass)
			throws CancelledException {

		List<RecoveredClass> ancestorsWithoutVfunctions = new ArrayList<RecoveredClass>();

		List<RecoveredClass> classHierarchy = recoveredClass.getClassHierarchy();

		Iterator<RecoveredClass> hierarchyIterator = classHierarchy.iterator();

		while (hierarchyIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass ancestorClass = hierarchyIterator.next();

			// skip self
			if (ancestorClass.equals(recoveredClass)) {
				continue;
			}
			if (!ancestorClass.hasVftable()) {
				ancestorsWithoutVfunctions.add(ancestorClass);
			}
		}

		return ancestorsWithoutVfunctions;
	}

	/**
	 * Method to test whether class has ancestor without virtual functions or not
	 * @param recoveredClass given class object
	 * @return true if class has an ancestor class without virtual functions
	 * @throws CancelledException if cancelled
	 */
	public boolean hasNonVirtualFunctionAncestor(RecoveredClass recoveredClass)
			throws CancelledException {
		List<RecoveredClass> classHierarchy = recoveredClass.getClassHierarchy();
		Iterator<RecoveredClass> recoveredClassIterator = classHierarchy.iterator();
		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass currentClass = recoveredClassIterator.next();
			if (!currentClass.hasVftable()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method to get all ancestor class constructors for the given class
	 * @param recoveredClass given class
	 * @return List of all functions that are constructors of an ancestor class of given class
	 * @throws CancelledException if script is cancelled
	 */
	public List<Function> getAllAncestorConstructors(RecoveredClass recoveredClass)
			throws CancelledException {

		List<Function> allAncestorConstructors = new ArrayList<Function>();

		List<RecoveredClass> classHierarchy = recoveredClass.getClassHierarchy();
		ListIterator<RecoveredClass> classHierarchyIterator = classHierarchy.listIterator(1);

		while (classHierarchyIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass currentClass = classHierarchyIterator.next();

			List<Function> constructorList =
				new ArrayList<Function>(currentClass.getConstructorList());
			constructorList.addAll(currentClass.getInlinedConstructorList());
			Iterator<Function> constructors = constructorList.iterator();
			while (constructors.hasNext()) {
				monitor.checkCanceled();
				Function constructor = constructors.next();
				if (!allAncestorConstructors.contains(constructor)) {
					allAncestorConstructors.add(constructor);
				}
			}
		}
		return allAncestorConstructors;
	}

	/**
	 * Method to retrieve a list of destructors for the ancestors of the given class
	 * @param recoveredClass the given class
	 * @return a list of destructors for the ancestors of the given class
	 * @throws CancelledException if cancelled
	 */
	public List<Function> getAncestorDestructors(RecoveredClass recoveredClass)
			throws CancelledException {

		List<Function> allAncestorDestructors = new ArrayList<Function>();

		List<RecoveredClass> classHierarchy = recoveredClass.getClassHierarchy();
		ListIterator<RecoveredClass> classHierarchyIterator = classHierarchy.listIterator(1);

		while (classHierarchyIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass parentClass = classHierarchyIterator.next();

			List<Function> destructorList =
				new ArrayList<Function>(parentClass.getDestructorList());
			destructorList.addAll(parentClass.getInlinedDestructorList());
			Iterator<Function> destructors = destructorList.iterator();
			while (destructors.hasNext()) {
				monitor.checkCanceled();
				Function destructor = destructors.next();
				if (!allAncestorDestructors.contains(destructor)) {
					allAncestorDestructors.add(destructor);
				}
			}
		}
		return allAncestorDestructors;
	}

	/**
	 * Method to retrieve all constructors of descendants of the given class
	 * @param recoveredClass the given class
	 * @return a list of all constructors of descendants of the given class
	 * @throws CancelledException if cancelled
	 */
	public List<Function> getAllDescendantConstructors(RecoveredClass recoveredClass)
			throws CancelledException {

		List<Function> allDescendantConstructors = new ArrayList<Function>();

		List<RecoveredClass> childClasses = recoveredClass.getChildClasses();
		Iterator<RecoveredClass> childClassIterator = childClasses.iterator();
		while (childClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass childClass = childClassIterator.next();

			List<Function> constructorList =
				new ArrayList<Function>(childClass.getConstructorList());
			constructorList.addAll(childClass.getInlinedConstructorList());
			Iterator<Function> constructors = constructorList.iterator();
			while (constructors.hasNext()) {
				monitor.checkCanceled();
				Function constructor = constructors.next();
				if (!allDescendantConstructors.contains(constructor)) {
					allDescendantConstructors.add(constructor);
				}
			}
			allDescendantConstructors.addAll(getAllDescendantConstructors(childClass));
		}

		return allDescendantConstructors;
	}

	/**
	 * Method to retrieve all destructors of descendants of the given class
	 * @param recoveredClass the given class
	 * @return a list of all destructors of descendants of the given class
	 * @throws CancelledException if cancelled
	 */
	public List<Function> getAllDescendantDestructors(RecoveredClass recoveredClass)
			throws CancelledException {

		List<Function> allDescendantDestructors = new ArrayList<Function>();

		List<RecoveredClass> childClasses = recoveredClass.getChildClasses();
		Iterator<RecoveredClass> childClassIterator = childClasses.iterator();
		while (childClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass childClass = childClassIterator.next();

			List<Function> destructorList = new ArrayList<Function>(childClass.getDestructorList());
			destructorList.addAll(childClass.getInlinedDestructorList());
			Iterator<Function> destructors = destructorList.iterator();
			while (destructors.hasNext()) {
				monitor.checkCanceled();
				Function destructor = destructors.next();
				if (!allDescendantDestructors.contains(destructor)) {
					allDescendantDestructors.add(destructor);
				}
			}
			allDescendantDestructors.addAll(getAllDescendantDestructors(childClass));
		}

		return allDescendantDestructors;
	}

	/**
	 * Method to retrieve a list of possible parent class constructors to the given function
	 * @param function the given function
	 * @return a list of possible parent class constructors to the given function
	 * @throws CancelledException if cancelled
	 */
	public List<Function> getPossibleParentConstructors(Function function)
			throws CancelledException {

		List<Function> possibleParentConstructors = new ArrayList<Function>();

		List<ReferenceAddressPair> refAddrPairList = getCalledConstDestRefAddrPairs(function);

		List<Address> vftableReferenceList = getVftableReferences(function);
		if (vftableReferenceList == null) {
			return possibleParentConstructors;
		}

		Address minVftableReference = extendedFlatAPI.getMinimumAddressOnList(vftableReferenceList);
		Iterator<ReferenceAddressPair> iterator = refAddrPairList.iterator();

		while (iterator.hasNext()) {
			monitor.checkCanceled();
			ReferenceAddressPair refAddrPair = iterator.next();
			Address sourceAddr = refAddrPair.getSource();
			if (sourceAddr.compareTo(minVftableReference) < 0) {
				Function calledFunction = api.getFunctionAt(refAddrPair.getDestination());
				if (calledFunction != null) {
					possibleParentConstructors.add(calledFunction);
				}
			}
		}
		return possibleParentConstructors;
	}

	/**
	 * Method to retrieve a single common function on both lists
	 * @param list1 first list of functions
	 * @param list2 second list of functions
	 * @return single function if there is one function on both lists, null if there are none or
	 * more than one
	 */
	public Function getFunctionOnBothLists(List<Function> list1, List<Function> list2) {

		List<Function> commonFunctions = getFunctionsOnBothLists(list1, list2);

		if (commonFunctions.size() == 1) {
			return commonFunctions.get(0);
		}
		// if none or more than one return null
		return null;
	}

	public List<Function> getFunctionsOnBothLists(List<Function> list1, List<Function> list2) {
		List<Function> commonFunctions =
			list1.stream().distinct().filter(list2::contains).collect(Collectors.toList());

		return commonFunctions;
	}

	/**
	 * Method to retrieve a set of functions contained in both of the given sets of functions
	 * @param set1 the first set of functions
	 * @param set2 the second set of functions
	 * @return a set of functions contained in both of the given sets of functions
	 */
	public Set<Function> getFunctionsContainedInBothSets(Set<Function> set1, Set<Function> set2) {
		Set<Function> commonFunctions =
			set1.stream().distinct().filter(set2::contains).collect(Collectors.toSet());

		return commonFunctions;

	}

	/**
	 * Method to retrieve a list of possible parent class destructors to the given function
	 * @param function the given function
	 * @return a list of possible parent class destructors to the given function
	 * @throws CancelledException if cancelled
	 */
	public List<Function> getPossibleParentDestructors(Function function)
			throws CancelledException {

		List<Function> possibleParentDestructors = new ArrayList<Function>();

		List<ReferenceAddressPair> refAddrPairList = getCalledConstDestRefAddrPairs(function);

		List<Address> vftableReferenceList = getVftableReferences(function);
		if (vftableReferenceList == null) {
			return possibleParentDestructors;
		}

		Address maxVftableReference = extendedFlatAPI.getMaximumAddressOnList(vftableReferenceList);
		Iterator<ReferenceAddressPair> iterator = refAddrPairList.iterator();

		while (iterator.hasNext()) {
			monitor.checkCanceled();
			ReferenceAddressPair refAddrPair = iterator.next();
			Address sourceAddr = refAddrPair.getSource();
			if (sourceAddr.compareTo(maxVftableReference) > 0) {
				Function calledFunction =
					extendedFlatAPI.getFunctionAt(refAddrPair.getDestination());
				if (calledFunction != null) {
					possibleParentDestructors.add(calledFunction);
				}
			}
		}
		return possibleParentDestructors;
	}

	/**
	 * Method to determine the constructors/destructors using known parent
	 * @param recoveredClass RecoveredClass object
	 * @param parentClass possible parent class of the given RecoveredClass
	 * @return true if processed successfully, else false
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if issue setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	public boolean processConstructorsAndDestructorsUsingParent(RecoveredClass recoveredClass,
			RecoveredClass parentClass) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		if (parentClass == null) {
			return false;
		}

		Map<Function, Function> childParentConstructorMap = new HashMap<Function, Function>();
		Map<Function, Function> childParentDestructorMap = new HashMap<Function, Function>();

		List<Function> constDestFunctions =
			new ArrayList<Function>(recoveredClass.getConstructorOrDestructorFunctions());
		constDestFunctions.removeAll(recoveredClass.getIndeterminateInlineList());

		List<Function> parentConstDestFunctions = parentClass.getConstructorOrDestructorFunctions();

		List<Function> parentConstructors = getAllClassConstructors(parentClass);
		List<Function> parentDestructors = getAllClassDestructors(parentClass);
		List<Function> childConstructors = getAllClassConstructors(recoveredClass);
		List<Function> childDestructors = getAllClassDestructors(recoveredClass);

		Iterator<Function> constDestIterator = constDestFunctions.iterator();
		while (constDestIterator.hasNext()) {
			monitor.checkCanceled();

			Function constDestFunction = constDestIterator.next();

			// based on call order get possible parent constructors for the given function
			List<Function> possibleParentConstructors =
				getPossibleParentConstructors(constDestFunction);

			// remove any known destructors since they can't also be constructors - rarely these
			// show up on possible const list
			possibleParentConstructors.removeAll(parentDestructors);

			Function parentConstructor =
				getFunctionOnBothLists(possibleParentConstructors, parentConstDestFunctions);

			// another sanity check - make sure child function isn't a known destructor
			if (parentConstructor != null && !childDestructors.contains(constDestFunction)) {
				childParentConstructorMap.put(constDestFunction, parentConstructor);
				continue;
			}

			// based on call order get possible parent destructors for the given function
			List<Function> possibleParentDestructors =
				getPossibleParentDestructors(constDestFunction);

			// remove any known constructors since they can't also be destructors - rarely these
			// show up on possible dest list
			possibleParentDestructors.removeAll(parentConstructors);

			Function parentDestructor =
				getFunctionOnBothLists(possibleParentDestructors, parentConstDestFunctions);

			// another sanity check - make sure child function isn't a known constructor
			if (parentDestructor != null && !childConstructors.contains(constDestFunction)) {
				childParentDestructorMap.put(constDestFunction, parentDestructor);
				continue;
			}
		}

		// check to make sure there is no overlap in the poss c and poss d maps
		Set<Function> constructorKeySet = childParentConstructorMap.keySet();
		Set<Function> destructorKeySet = childParentDestructorMap.keySet();
		Set<Function> functionsContainedInBothSets =
			getFunctionsContainedInBothSets(constructorKeySet, destructorKeySet);

		if (functionsContainedInBothSets.size() > 0) {
			constructorKeySet.removeAll(functionsContainedInBothSets);
			destructorKeySet.removeAll(functionsContainedInBothSets);
			if (constructorKeySet.isEmpty() && destructorKeySet.isEmpty()) {
				return false;
			}
		}

		// once all checks pass, add both the child and parent constructors to their class
		// constructor list and remove from the indeterminate lists
		// the addConstructor method processes the offsets and types for the initialized class data
		Iterator<Function> childConstructorIterator = constructorKeySet.iterator();
		while (childConstructorIterator.hasNext()) {
			monitor.checkCanceled();
			Function childConstructor = childConstructorIterator.next();
			addConstructorToClass(recoveredClass, childConstructor);
			recoveredClass.removeIndeterminateConstructorOrDestructor(childConstructor);
			Function parentConstructor = childParentConstructorMap.get(childConstructor);
			addConstructorToClass(parentClass, parentConstructor);
			parentClass.removeIndeterminateConstructorOrDestructor(parentConstructor);
		}

		// Do the same for the child/parent destructors
		Iterator<Function> childDestructorIterator = destructorKeySet.iterator();
		while (childDestructorIterator.hasNext()) {
			monitor.checkCanceled();
			Function childDestructor = childDestructorIterator.next();
			addDestructorToClass(recoveredClass, childDestructor);
			recoveredClass.removeIndeterminateConstructorOrDestructor(childDestructor);
			Function parentDestructor = childParentDestructorMap.get(childDestructor);
			addDestructorToClass(parentClass, parentDestructor);
			parentClass.removeIndeterminateConstructorOrDestructor(parentDestructor);
		}
		return true;

	}

	/**
	 * Method to retrieve all types of class destructors including normal destructors, non-this
	 * destructors and inline destructors(does not include deleting destructors since they are
	 * really a vfunction)
	 * @param recoveredClass the given class
	 * @return the list of all destructors for the given class
	 */
	public List<Function> getAllClassDestructors(RecoveredClass recoveredClass) {

		List<Function> allClassDestructors = new ArrayList<Function>();
		allClassDestructors.addAll(recoveredClass.getDestructorList());
		allClassDestructors.addAll(recoveredClass.getNonThisDestructors());
		allClassDestructors.addAll(recoveredClass.getInlinedDestructorList());

		return allClassDestructors;
	}

	/**
	 * Method to retrieve all types of class constructors including normal constructors and inline
	 * constructors
	 * @param recoveredClass the given class
	 * @return the list of all constructors for the given class
	 */
	public List<Function> getAllClassConstructors(RecoveredClass recoveredClass) {

		List<Function> allClassConstructors = new ArrayList<Function>();

		allClassConstructors.addAll(recoveredClass.getConstructorList());
		allClassConstructors.addAll(recoveredClass.getInlinedConstructorList());

		return allClassConstructors;

	}

	/**
	 * Method to add constructor function to the given class and collect member data information
	 * @param recoveredClass given class
	 * @param constructorFunction given function
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if error setting return type
	 * @throws DuplicateNameException  if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	public void addConstructorToClass(RecoveredClass recoveredClass, Function constructorFunction)
			throws CancelledException, InvalidInputException, DuplicateNameException,
			CircularDependencyException {

		// skip if already a constructor for this class
		if (recoveredClass.getConstructorList().contains(constructorFunction)) {
			return;
		}

		// create vftable mapping for any class that didn't have a constructor when the
		// original mappings were created
		if (recoveredClass.getOrderToVftableMap().size() == 0) {
			createVftableOrderMapping(recoveredClass);
		}

		recoveredClass.addConstructor(constructorFunction);
		addToAllConstructors(constructorFunction);
	}

	/**
	 * Method to add inlined constructor function to the given class
	 * @param recoveredClass given class
	 * @param inlinedConstructorFunction given function
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void addInlinedConstructorToClass(RecoveredClass recoveredClass,
			Function inlinedConstructorFunction)
			throws InvalidInputException, DuplicateNameException {

		recoveredClass.addInlinedConstructor(inlinedConstructorFunction);
		addToAllInlinedConstructors(inlinedConstructorFunction);
	}

	/**
	 * Method to add destructor to the given class
	 * @param recoveredClass given class
	 * @param destructorFunction given destructor function
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void addDestructorToClass(RecoveredClass recoveredClass, Function destructorFunction)
			throws InvalidInputException, DuplicateNameException {

		recoveredClass.addDestructor(destructorFunction);
		addToAllDestructors(destructorFunction);
	}

	/**
	 * Method to add inlined destructor to the given class
	 * @param recoveredClass given class
	 * @param inlinedDestructorFunction given function
	 * @throws InvalidInputException if error setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void addInlinedDestructorToClass(RecoveredClass recoveredClass,
			Function inlinedDestructorFunction)
			throws InvalidInputException, DuplicateNameException {

		recoveredClass.addInlinedDestructor(inlinedDestructorFunction);
		addToAllInlinedDestructors(inlinedDestructorFunction);
	}

	/**
	 * Method to create a mapping between the order it appears and the vftable for the given class
	 * @param recoveredClass the given class
	 * @throws CancelledException if cancelled
	 */
	public void createVftableOrderMapping(RecoveredClass recoveredClass) throws CancelledException {

		if (!recoveredClass.hasVftable()) {
			return;
		}

		List<Address> vftableAddresses = recoveredClass.getVftableAddresses();

		Map<Integer, Address> classOffsetToVftableMap = recoveredClass.getClassOffsetToVftableMap();

		if (classOffsetToVftableMap.size() == 0) {
			return;
		}

		if (vftableAddresses.size() != classOffsetToVftableMap.size()) {
			if (DEBUG) {
				Msg.debug(this, recoveredClass.getName() + " has " + vftableAddresses.size() +
					" vftables but " + classOffsetToVftableMap.size() + " offset to vftable maps");
			}
		}

		List<Integer> offsetList = new ArrayList<Integer>(classOffsetToVftableMap.keySet());

		Collections.sort(offsetList);

		int order = 0;
		Iterator<Integer> offsetIterator = offsetList.iterator();
		while (offsetIterator.hasNext()) {
			monitor.checkCanceled();
			Integer offset = offsetIterator.next();
			Address vftableAddress = classOffsetToVftableMap.get(offset);
			recoveredClass.addOrderToVftableMapping(order, vftableAddress);
			order++;
		}
	}

	/**
	 * Method to create a map for each class between the order a vftable is seen in a class and the vftable itself
	 * @param recoveredClasses list of classes to processes
	 * @throws CancelledException if cancelled
	 */
	public void createVftableOrderMap(List<RecoveredClass> recoveredClasses)
			throws CancelledException {
		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();

			// create a mapping of the order of the vftable to the vftable address and save to class
			createVftableOrderMapping(recoveredClass);
		}
	}

	/**
	 *
	 * @param referenceToClassMap map of references to the class that contains the referenced function
	 * @param referencesToConstructors list of addresses referring to constructors
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if error setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	public void createListedConstructorFunctions(Map<Address, RecoveredClass> referenceToClassMap,
			List<Address> referencesToConstructors) throws CancelledException,
			InvalidInputException, DuplicateNameException, CircularDependencyException {

		Iterator<Address> constructorIterator = referencesToConstructors.iterator();
		while (constructorIterator.hasNext()) {
			monitor.checkCanceled();

			Address constructorReference = constructorIterator.next();
			RecoveredClass recoveredClass = referenceToClassMap.get(constructorReference);

			Function constructor =
				extendedFlatAPI.getReferencedFunction(constructorReference, true);

			if (recoveredClass.getIndeterminateList().contains(constructor)) {
				addConstructorToClass(recoveredClass, constructor);
				recoveredClass.removeIndeterminateConstructorOrDestructor(constructor);
				continue;
			}

			if (recoveredClass.getIndeterminateInlineList().contains(constructor)) {
				processInlineConstructor(recoveredClass, constructor, referenceToClassMap);
			}
		}
	}

	/**
	 * Method to process a found inlined constructor
	 * @param recoveredClass the class being processed
	 * @param inlinedConstructorFunction the function containing an inlined ancestor constructor
	 * @param referenceToClassMap the map of references to classes
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if issue setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */

	public void processInlineConstructor(RecoveredClass recoveredClass,
			Function inlinedConstructorFunction, Map<Address, RecoveredClass> referenceToClassMap)
			throws CancelledException, InvalidInputException, DuplicateNameException,
			CircularDependencyException {

		if (referenceToClassMap.isEmpty()) {
			return;
		}

		List<Address> referencesToVftables = new ArrayList<Address>();

		List<Address> referenceAddresses = new ArrayList<Address>(referenceToClassMap.keySet());
		Iterator<Address> referenceIterator = referenceAddresses.iterator();
		while (referenceIterator.hasNext()) {
			monitor.checkCanceled();
			Address reference = referenceIterator.next();
			Address vftableAddress = getVftableAddress(reference);
			if (vftableAddress != null) {
				referencesToVftables.add(reference);
			}
		}

		if (referencesToVftables.isEmpty()) {
			return;
		}

		Collections.sort(referencesToVftables);

		int numRefs = referencesToVftables.size();
		Address lastRef = referencesToVftables.get(numRefs - 1);

		Iterator<Address> refToVtablesIterator = referencesToVftables.iterator();
		while (refToVtablesIterator.hasNext()) {
			monitor.checkCanceled();
			Address refToVftable = refToVtablesIterator.next();
			RecoveredClass referencedClass = referenceToClassMap.get(refToVftable);

			// last reference is the constructor
			if (refToVftable.equals(lastRef)) {
				addConstructorToClass(referencedClass, inlinedConstructorFunction);
			}
			// the rest are inlined constructors
			else {
				addInlinedConstructorToClass(referencedClass, inlinedConstructorFunction);

			}
			referencedClass.removeIndeterminateInline(inlinedConstructorFunction);
		}

		return;

	}

	/**
	 * Method to process an inlinedDestructor function
	 * @param recoveredClass the class the inlinedDestructor is in
	 * @param inlinedDestructorFunction the inlined function
	 * @param referenceToClassMap the map of references to classes
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if issue setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void processInlineDestructor(RecoveredClass recoveredClass,
			Function inlinedDestructorFunction, Map<Address, RecoveredClass> referenceToClassMap)
			throws CancelledException, InvalidInputException, DuplicateNameException {

		if (referenceToClassMap.isEmpty()) {
			return;
		}

		List<Address> referencesToVftables = new ArrayList<Address>();

		List<Address> referenceAddresses = new ArrayList<Address>(referenceToClassMap.keySet());
		Iterator<Address> referenceIterator = referenceAddresses.iterator();
		while (referenceIterator.hasNext()) {
			monitor.checkCanceled();
			Address reference = referenceIterator.next();
			Address vftableAddress = getVftableAddress(reference);
			if (vftableAddress != null) {
				referencesToVftables.add(reference);
			}
		}

		if (referencesToVftables.isEmpty()) {
			return;
		}

		// reverse sort
		Collections.sort(referencesToVftables, Collections.reverseOrder());

		int numRefs = referencesToVftables.size();
		Address lastRef = referencesToVftables.get(numRefs - 1);

		Iterator<Address> refToVtablesIterator = referencesToVftables.iterator();
		while (refToVtablesIterator.hasNext()) {
			monitor.checkCanceled();
			Address refToVftable = refToVtablesIterator.next();
			RecoveredClass referencedClass = referenceToClassMap.get(refToVftable);

			// last reference is the constructor
			if (refToVftable.equals(lastRef)) {
				addDestructorToClass(referencedClass, inlinedDestructorFunction);
			}
			// the rest are inlined constructors
			else {
				addInlinedDestructorToClass(referencedClass, inlinedDestructorFunction);

			}
			referencedClass.removeIndeterminateInline(inlinedDestructorFunction);
		}

		return;

	}

	/**
	 * Method to get the address that references the first vftable in the given function
	 * @param function the given function
	 * @return the address in the given function that references the first referenced vftable or
	 * null if no vftable is referenced in the given function
	 */
	public Address getFirstVftableReferenceInFunction(Function function) {

		List<Address> vftableReferenceList = getVftableReferences(function);

		Collections.sort(vftableReferenceList);

		return vftableReferenceList.get(0);

	}

	/**
	 * Method to make the given function a thiscall
	 * @param function the given function
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void makeFunctionThiscall(Function function)
			throws InvalidInputException, DuplicateNameException {

		if (function.getCallingConventionName().equals(CompilerSpec.CALLING_CONVENTION_thiscall)) {
			return;
		}

		ReturnParameterImpl returnType =
			new ReturnParameterImpl(function.getSignature().getReturnType(), program);

		function.updateFunction(CompilerSpec.CALLING_CONVENTION_thiscall, returnType,
			FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS, true, function.getSignatureSource(),
			function.getParameters());
	}

	/**
	 * Method to determine if the given function calls a known constructor or inlined constructor
	 * @param function the given function
	 * @return true if function calls a known constructor or inlined constructor, false otherwise
	 * @throws CancelledException if cancelled
	 */
	public boolean callsKnownConstructor(Function function) throws CancelledException {

		List<ReferenceAddressPair> calledFunctionRefAddrPairs =
			getCalledConstDestRefAddrPairs(function);

		Iterator<ReferenceAddressPair> calledFunctionIterator =
			calledFunctionRefAddrPairs.iterator();
		while (calledFunctionIterator.hasNext()) {

			monitor.checkCanceled();

			ReferenceAddressPair referenceAddressPair = calledFunctionIterator.next();

			Address calledFunctionAddress = referenceAddressPair.getDestination();
			Function calledFunction = extendedFlatAPI.getFunctionAt(calledFunctionAddress);

			if (calledFunction.isThunk()) {
				calledFunction = calledFunction.getThunkedFunction(true);
			}

			if (getAllConstructors().contains(calledFunction) ||
				getAllInlinedConstructors().contains(calledFunction)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method to determine if the given function calls a known constructor or inlined constructor
	 * @param function the given function
	 * @return true if function calls a known constructor or inlined constructor, false otherwise
	 * @throws CancelledException if cancelled
	 */
	public boolean callsKnownDestructor(Function function) throws CancelledException {

		List<ReferenceAddressPair> calledFunctionRefAddrPairs =
			getCalledConstDestRefAddrPairs(function);

		Iterator<ReferenceAddressPair> calledFunctionIterator =
			calledFunctionRefAddrPairs.iterator();
		while (calledFunctionIterator.hasNext()) {

			monitor.checkCanceled();

			ReferenceAddressPair referenceAddressPair = calledFunctionIterator.next();

			Address calledFunctionAddress = referenceAddressPair.getDestination();
			Function calledFunction = extendedFlatAPI.getFunctionAt(calledFunctionAddress);

			if (calledFunction.isThunk()) {
				calledFunction = calledFunction.getThunkedFunction(true);
			}

			if (getAllDestructors().contains(calledFunction) ||
				getAllInlinedDestructors().contains(calledFunction)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method to get the total number of constructors in the given list of classes
	 * @param recoveredClasses list of classes to process
	 * @return number of constructor functions in all classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfConstructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();
			List<Function> constructorList = recoveredClass.getConstructorList();
			total += constructorList.size();
		}
		return total;
	}

	/**
	 * Method to return the total number of destructors in the given list of classes
	 * @param recoveredClasses the list of classes
	 * @return the total number of destructors in the given list of classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfDestructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			int numDestructors = recoveredClass.getDestructorList().size();
			total += numDestructors;
		}
		return total;
	}

	/**
	 * Method to return the total number of inlined destructors in the given list of classes
	 * @param recoveredClasses the list of classes
	 * @return the total number of inlined destructors in the given list of classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfInlineDestructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			int numInlinedDestructors = recoveredClass.getInlinedDestructorList().size();
			total += numInlinedDestructors;
		}
		return total;
	}

	/**
	 * Method to get the total number of deleting destructors in the given list of classes
	 * @param recoveredClasses the list of classes
	 * @return the total number of deleting destructors in the given list of classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfDeletingDestructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			List<Function> deletingDestructors = recoveredClass.getDeletingDestructors();
			total += deletingDestructors.size();
		}
		return total;
	}

	/**
	 * Method to retrieve the total number of clone functions assigned to all the classes
	 * @param recoveredClasses List of classes
	 * @return total number of clone functions assigned to classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfCloneFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();
			List<Function> cloneFunctions = recoveredClass.getCloneFunctions();
			total += cloneFunctions.size();
		}
		return total;
	}

	/**
	 * Method to return the total number of vbase destructors in the given list of classes
	 * @param recoveredClasses the list of classes
	 * @return the the total number of vbase destructors in the given list of classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfVBaseFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			Function cloneFunction = recoveredClass.getVBaseDestructor();
			if (cloneFunction != null) {
				total++;
			}
		}
		return total;
	}

	/**
	 * Method to get the total number of virtual functions (or functions that inherit from virtual functions) in the given list of classes
	 * @param recoveredClasses the list of classes
	 * @return the total number of virtual functions (or functions that inherit from virtual functions) in the given list of classes
	 * @throws CancelledException if cancelled
	 */
	public int getNumberOfVirtualFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		int total = 0;
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			List<Function> vfunctionList = recoveredClass.getAllVirtualFunctions();
			if (vfunctionList == null) {
				continue;
			}

			total += vfunctionList.size();

		}
		return total;
	}

	/**
	 * Method to get a list of functions from the list of classes that could not be determined whether they
	 * were constructors or destructors
	 * @param recoveredClasses the list of classes
	 * @return list of functions from the list of classes that could not be determined whether they
	 * were constructors or destructors
	 * @throws CancelledException if cancelled
	 */
	public List<Function> getRemainingIndeterminates(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		List<Function> remainingIndeterminates = new ArrayList<Function>();
		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			List<Function> indeterminateConstructorOrDestructorList =
				recoveredClass.getIndeterminateList();
			remainingIndeterminates.addAll(indeterminateConstructorOrDestructorList);

			List<Function> indeterminateInlines = recoveredClass.getIndeterminateInlineList();
			remainingIndeterminates.addAll(indeterminateInlines);

		}
		return remainingIndeterminates;
	}

	/**
	 *
	 * @param referenceToClassMap map from reference to class the referenced function is in
	 * @param referencesToDestructors list of addresses referring to destructors
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if error setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void createListedDestructorFunctions(Map<Address, RecoveredClass> referenceToClassMap,
			List<Address> referencesToDestructors)
			throws CancelledException, InvalidInputException, DuplicateNameException {

		Iterator<Address> destructorIterator = referencesToDestructors.iterator();
		while (destructorIterator.hasNext()) {
			monitor.checkCanceled();

			Address destructorReference = destructorIterator.next();
			RecoveredClass recoveredClass = referenceToClassMap.get(destructorReference);

			Function destructor =
				extendedFlatAPI.getReferencedFunction(destructorReference, true);

			if (recoveredClass.getIndeterminateList().contains(destructor)) {
				addDestructorToClass(recoveredClass, destructor);
				recoveredClass.removeIndeterminateConstructorOrDestructor(destructor);
				continue;
			}

			if (recoveredClass.getIndeterminateInlineList().contains(destructor)) {
				processInlineDestructor(recoveredClass, destructor, referenceToClassMap);
			}
		}
	}

	/**
	 * Method to use existing pdb names to assign class constructors and destructors
	 * @param recoveredClasses List of classes
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if error setting return type
	 * @throws CancelledException if cancelled
	 */
	public void assignConstructorsAndDestructorsUsingExistingName(
			List<RecoveredClass> recoveredClasses) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();
		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();
			List<Function> indeterminateFunctions = recoveredClass.getIndeterminateList();
			Iterator<Function> functionIterator = indeterminateFunctions.iterator();
			while (functionIterator.hasNext()) {

				monitor.checkCanceled();

				Function function = functionIterator.next();
				Namespace namespace = function.getParentNamespace();
				if (!namespace.equals(recoveredClass.getClassNamespace())) {
					continue;
				}
				String name = function.getName();
				if (name.equals(recoveredClass.getName())) {
					addConstructorToClass(recoveredClass, function);
					functionIterator.remove();
					continue;
				}
				if (name.equals("~" + recoveredClass.getName())) {
					addDestructorToClass(recoveredClass, function);
					functionIterator.remove();
					continue;
				}
			}

		}
	}

	/**
	 * Method to determine if a class's identified vbase_destructor is valid or not
	 * If the class has both a vbase destructor and a regular destructor and the class has
	 * a non-virtual ancestor, and either the class is the lowest child or has a child with a
	 * vbase_destructor then it is a valid vbase_destructor. Otherwise, it isn't.
	 * @param recoveredClass the given class object
	 * @return true if class has a vbase destructor, false if not
	 */
	private boolean hasVbaseDestructor(RecoveredClass recoveredClass) throws CancelledException {
		Function vBaseDestructor = recoveredClass.getVBaseDestructor();

		StringBuffer string = new StringBuffer();
		string.append(recoveredClass.getName());

		if (vBaseDestructor == null) {
			return false;
		}
		if (recoveredClass.getDestructorList().size() != 1) {
			return false;
		}

		if (!hasNonVirtualFunctionAncestor(recoveredClass)) {
			return false;
		}

		if (!recoveredClass.hasChildClass() || hasChildWithVBaseAndDestructor(recoveredClass)) {
			return true;
		}
		return false;
	}

	/**
	 * Method to determine if the given class has a child class with both a vbase destructor and a
	 * regular destructor
	 * @param recoveredClass the given class
	 * @return true if the given class has a child class with both a vbase destructor and a regular
	 * destructor, false otherwise
	 * @throws CancelledException if cancelled
	 */
	public boolean hasChildWithVBaseAndDestructor(RecoveredClass recoveredClass)
			throws CancelledException {
		if (recoveredClass.hasChildClass()) {
			List<RecoveredClass> childClasses = recoveredClass.getChildClasses();
			Iterator<RecoveredClass> childIterator = childClasses.iterator();
			while (childIterator.hasNext()) {
				monitor.checkCanceled();
				RecoveredClass childClass = childIterator.next();
				if (childClass.getDestructorList().size() == 1 &&
					childClass.getVBaseDestructor() != null) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Method to create a new recovered class object and add it to the namespaceToClassMap
	 * @param namespace the namespace to put the new class in
	 * @param hasVftable true if class has at least one vftable, false otherwise
	 * @return the RecoveredClass object
	 * @throws CancelledException if cancelled
	 */
	public RecoveredClass createNewClass(Namespace namespace, boolean hasVftable)
			throws CancelledException {

		String className = namespace.getName();

		CategoryPath classPath = extendedFlatAPI
				.createDataTypeCategoryPath(classDataTypesCategoryPath, namespace);

		RecoveredClass newClass =
			new RecoveredClass(className, classPath, namespace, dataTypeManager);
		newClass.setHasVftable(hasVftable);

		updateNamespaceToClassMap(namespace, newClass);
		return newClass;

	}

	/**
	 * Method to recover the class information for each vftable symbol on the list
	 * * For each virtual function table:
	 * 1. get vftable's existing class
	 * 2. create matching data type category folder in dt manager
	 * 3. get list of virtual functions
	 * 4. create RecoveredClass object for the vftable class
	 * 5. add mapping from vftableAddress to class
	 * 6. add list of const/dest functions to RecoveredClass object
	 * 7. update list of all const/dest functions in currenetProgram
	 * 8. set RecoveredClass indeterminate list to const/dest list
	 * 9. update list of all indeterminate const/dest
	 * @param vftableSymbolList List of vftable symbols
	 * @param allowNullFunctionPtrs if true, allow existance of null pointers in vftable
	 * @param allowDefaultRefsInMiddle if true, allow existance of default refs into the middle of a vftable
	 * @return List of RecoveredClass objects created corresponding to the vftable symbols
	 * @throws CancelledException if cancelled
	 * @throws Exception if issues getting data
	 */
	List<RecoveredClass> recoverClassesFromVftables(List<Symbol> vftableSymbolList,
			boolean allowNullFunctionPtrs, boolean allowDefaultRefsInMiddle)
			throws CancelledException, Exception {

		List<RecoveredClass> recoveredClasses = new ArrayList<RecoveredClass>();

		Iterator<Symbol> vftableSymbolsIterator = vftableSymbolList.iterator();
		while (vftableSymbolsIterator.hasNext()) {
			monitor.checkCanceled();
			Symbol vftableSymbol = vftableSymbolsIterator.next();
			Address vftableAddress = vftableSymbol.getAddress();

			// Get class name from class vftable is in
			Namespace vftableNamespace = vftableSymbol.getParentNamespace();
			if (vftableNamespace.equals(globalNamespace)) {
				if (DEBUG) {
					Msg.debug(this,
						"vftable is in the global namespace, ie not in a class namespace, so cannot process");
				}
				continue;
			}

			// get only the functions from the ones that are not already processed structures
			// return null if not an unprocessed table
			List<Function> virtualFunctions = getFunctionsFromVftable(vftableAddress, vftableSymbol,
				allowNullFunctionPtrs, allowDefaultRefsInMiddle);

			// the vftable has already been processed - skip it
			if (virtualFunctions == null) {
				continue;
			}

			// Check to see if already have an existing RecoveredClass object for the
			// class associated with the current vftable.
			RecoveredClass recoveredClass = getClass(vftableNamespace);

			if (recoveredClass == null) {
				// Create a RecoveredClass object for the current class
				recoveredClass = createNewClass(vftableNamespace, true);
				recoveredClass.addVftableAddress(vftableAddress);
				recoveredClass.addVftableVfunctionsMapping(vftableAddress, virtualFunctions);

				// add it to the running list of RecoveredClass objects
				recoveredClasses.add(recoveredClass);
			}
			else {
				recoveredClass.addVftableAddress(vftableAddress);
				recoveredClass.addVftableVfunctionsMapping(vftableAddress, virtualFunctions);
				if (!recoveredClasses.contains(recoveredClass)) {
					recoveredClasses.add(recoveredClass);
				}

			}

			// add it to the vftableAddress to Class map
			updateVftableToClassMap(vftableAddress, recoveredClass);

			List<Address> referencesToVftable = getReferencesToVftable(vftableAddress);
			addReferenceToVtableMapping(referencesToVftable, vftableAddress);

			Map<Address, Function> vftableReferenceToFunctionMapping =
				createVftableReferenceToFunctionMapping(referencesToVftable);

			//vftableReferenceToFunctionMapping
			List<Function> possibleConstructorDestructorsForThisClass =
				findPossibleConstructorDestructors(vftableReferenceToFunctionMapping);

			addFunctionsToClassMapping(possibleConstructorDestructorsForThisClass, recoveredClass);

			// add the vftable reference to function mapping to the global list
			addFunctionToVftableReferencesMapping(vftableReferenceToFunctionMapping);

			// add the possible constructor/destructor list to the class
			recoveredClass.addConstructorDestructorList(possibleConstructorDestructorsForThisClass);
			recoveredClass.addIndeterminateConstructorOrDestructorList(
				possibleConstructorDestructorsForThisClass);

			// Add them to the list of all possible constructors and destructors in program			
			updateAllClassFunctionsWithVftableRefList(possibleConstructorDestructorsForThisClass);

			// add virtualFunctions to the global list of them
			updateAllVfunctionsSet(virtualFunctions);

		} // end of looping over vfTables
		return recoveredClasses;
	}

	public void promoteClassNamespaces(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();

			RecoveredClass recoveredClass = classIterator.next();
			Namespace classNamespace = recoveredClass.getClassNamespace();
			promoteNamespaces(classNamespace);
		}
	}

	private boolean promoteNamespaces(Namespace namespace) throws CancelledException {

		while (!namespace.isGlobal()) {

			monitor.checkCanceled();
			SymbolType namespaceType = namespace.getSymbol().getSymbolType();
			// if it is a namespace but not a class and it is in our namespace map (which makes
			// it a valid class) we need to promote it to a class namespace
			if (namespaceType != SymbolType.CLASS && namespaceType == SymbolType.NAMESPACE &&
				namespaceToClassMap.get(namespace) != null) {

				namespace = promoteToClassNamespace(namespace);
				if (namespace == null) {
					return false;
				}
				//if (DEBUG) {
				Msg.debug(this,
					"Promoted namespace " + namespace.getName(true) + " to a class namespace");
				//}
			}
			else {
				namespace = namespace.getParentNamespace();
			}
		}
		return true;
	}

	/**
	 * Method to promote the namespace is a class namespace.
	 * @return true if namespace is (now) a class namespace or false if it could not be promoted.
	 */
	private Namespace promoteToClassNamespace(Namespace namespace) {

		SymbolType symbolType = namespace.getSymbol().getSymbolType();
		if (symbolType == SymbolType.CLASS) {
			return namespace;
		}

		if (symbolType != SymbolType.NAMESPACE) {
			return namespace;
		}

		try {
			Namespace newClass = NamespaceUtils.convertNamespaceToClass(namespace);

			SymbolType newSymbolType = newClass.getSymbol().getSymbolType();
			if (newSymbolType == SymbolType.CLASS) {
				return newClass;
			}
			if (DEBUG) {
				Msg.debug(this,
					"Could not promote " + namespace.getName() + " to a class namespace");
			}
			return null;
		}
		catch (InvalidInputException e) {

			Msg.debug(this, "Could not promote " + namespace.getName() +
				" to a class namespace because " + e.getMessage());
			return null;
		}
	}

	/**
	 * Method to create mapping to possible constructor/destructor functions
	 * @param referencesToVftable list of references to a particular vftable
	 * @return Map of reference to vftable to the function it is in
	 * @throws CancelledException if cancelled
	 */
	private Map<Address, Function> createVftableReferenceToFunctionMapping(
			List<Address> referencesToVftable) throws CancelledException {

		Map<Address, Function> vftableRefToFunctionMapping = new HashMap<Address, Function>();
		Iterator<Address> referencesIterator = referencesToVftable.iterator();
		while (referencesIterator.hasNext()) {
			monitor.checkCanceled();
			Address vftableReference = referencesIterator.next();
			Function functionContaining = extendedFlatAPI.getFunctionContaining(vftableReference);
			if (functionContaining != null) {
				vftableRefToFunctionMapping.put(vftableReference, functionContaining);
			}
		}
		return vftableRefToFunctionMapping;
	}

	/**
	 * Method to generate a list of constructors and destructors given the mapping of the
	 * vftable to the functions that reference it
	 * @param vftableReferenceToFunctionMapping the mapping of vftable to the functions that reference the vftable
	 * @return a list of possible constructors or destructors using the given mapping
	 * @throws CancelledException if cancelled
	 */
	private List<Function> findPossibleConstructorDestructors(
			Map<Address, Function> vftableReferenceToFunctionMapping) throws CancelledException {

		List<Function> cdFunctions = new ArrayList<Function>();
		Set<Address> keySet = vftableReferenceToFunctionMapping.keySet();
		Iterator<Address> referencesIterator = keySet.iterator();
		while (referencesIterator.hasNext()) {
			monitor.checkCanceled();
			Address vtableReference = referencesIterator.next();
			Function function = vftableReferenceToFunctionMapping.get(vtableReference);
			if (!cdFunctions.contains(function)) {
				cdFunctions.add(function);
			}
		}
		return cdFunctions;
	}

	/**
	 * Method to get functions from vftable
	 * @param vftableAddress the address of the vftable
	 * @param vftableSymbol the name of the vftable
	 * @param allowNullFunctionPtrs if true, allow null pointers in table
	 * @param allowDefaultRefsInMiddle if true, allow references in middle of table
	 * @return the list of functions in the vftable
	 * @throws CancelledException if cancelled
	 * @throws Exception if issues getting data
	 */
	public List<Function> getFunctionsFromVftable(Address vftableAddress, Symbol vftableSymbol,
			boolean allowNullFunctionPtrs, boolean allowDefaultRefsInMiddle)
			throws CancelledException, Exception {

		List<Function> virtualFunctionList = new ArrayList<Function>();
		Data vftableData = program.getListing().getDefinedDataAt(vftableAddress);

		// now make sure the array or the structure is all pointers
		if (!extendedFlatAPI.isArrayOrStructureOfAllPointers(vftableData)) {
			// if it isn't an array of pointers then we don't know the size of the vftable
			// If undefined or pointers not in array or struct then see if what they are
			// pointing to are in the class already to determine size of array

			// create vtable
			int numFunctionPointers =
				createVftable(vftableAddress, allowNullFunctionPtrs, allowDefaultRefsInMiddle);
			if (numFunctionPointers == 0) {
				return null;
			}
			// make it an array
			vftableData = createVftableArray(vftableAddress, numFunctionPointers);
			if (vftableData == null) {
				return null;
			}
		}

		// if there is already a structure created there and it is
		// contained in the ClassDataTypes folder then it has already been processed so skip it
		if (vftableData.isStructure()) {
			String[] pathElements = vftableData.getDataType().getCategoryPath().getPathElements();
			if ((pathElements.length > 0) && (pathElements[0].equals(DTM_CLASS_DATA_FOLDER_NAME))) {
				return null;
			}
		}

		// Loop over the pointers in the vftable and add the pointed to functions to the list
		int numPointers = vftableData.getNumComponents();

		for (int i = 0; i < numPointers; ++i) {
			monitor.checkCanceled();

			Address functionPointerAddress = vftableData.getComponent(i).getAddress();
			if (allowNullFunctionPtrs && extendedFlatAPI.isNullPointer(functionPointerAddress)) {
				virtualFunctionList.add(null);
				continue;
			}

			Function function = extendedFlatAPI.getReferencedFunction(functionPointerAddress);

			if (function != null) {
				virtualFunctionList.add(function);
			}

		}
		return virtualFunctionList;

	}

	public Data createVftableArray(Address vftableAddress, int numFunctionPointers)
			throws CancelledException, AddressOutOfBoundsException {

		api.clearListing(vftableAddress,
			vftableAddress.add((numFunctionPointers * defaultPointerSize - 1)));

		DataType pointerDataType = dataTypeManager.getPointer(null);
		ArrayDataType vftableArrayDataType =
			new ArrayDataType(pointerDataType, numFunctionPointers, defaultPointerSize);
		try {
			Data vftableArrayData = api.createData(vftableAddress, vftableArrayDataType);
			return vftableArrayData;
		}
		catch (Exception e) {
			return null;
		}

	}

	/**
	 * Method to create an array of pointers at the given vftable address
	 * @param vftableAddress the vftable address
	 * @param allowNullFunctionPtrs if true allow vftables to have null pointers
	 * @param allowDefaultRefsInMiddle if true allow default references into the middle of the table
	 * @return the created array of pointers Data or null
	 * @throws CancelledException if cancelled
	 */
	public int createVftable(Address vftableAddress, boolean allowNullFunctionPtrs,
			boolean allowDefaultRefsInMiddle) throws CancelledException {

		int numFunctionPointers = 0;
		Address address = vftableAddress;
		MemoryBlock currentBlock = program.getMemory().getBlock(vftableAddress);

		boolean stillInCurrentTable = true;
		while (address != null && currentBlock.contains(address) && stillInCurrentTable &&
			extendedFlatAPI.isFunctionPointer(address, allowNullFunctionPtrs)) {
			numFunctionPointers++;
			address = address.add(defaultPointerSize);
			Symbol symbol = program.getSymbolTable().getPrimarySymbol(address);
			if (symbol == null) {
				continue;
			}
			// never let non-default refs
			if (symbol.getSource() != SourceType.DEFAULT) {
				stillInCurrentTable = false;
			}

			// if it gets here it is default
			if (!allowDefaultRefsInMiddle) {
				stillInCurrentTable = false;
			}
		}

		return numFunctionPointers;

	}

	/**
	 * Method to find references to vftables that are not in functions, either in undefined areas or
	 * instructions that are not in functions.
	 * @param vftableSymbols List of vftable symbols
	 * @return List of addresses where vftables are referenced but are not in a function
	 * @throws CancelledException when cancelled
	 */
	public List<Address> findVftableReferencesNotInFunction(List<Symbol> vftableSymbols)
			throws CancelledException {

		MemoryBytePatternSearcher searcher = new MemoryBytePatternSearcher("Vftable References");

		AddressSet searchSet = new AddressSet();
		AddressSetView executeSet = program.getMemory().getExecuteSet();
		AddressRangeIterator addressRanges = executeSet.getAddressRanges();
		while (addressRanges.hasNext()) {
			monitor.checkCanceled();
			AddressRange addressRange = addressRanges.next();
			searchSet.add(addressRange.getMinAddress(), addressRange.getMaxAddress());
		}

		List<Address> vftableAddresses = new ArrayList<Address>();

		List<Address> notInFunctionVftableRefs = new ArrayList<Address>();
		List<Address> newFunctions = new ArrayList<Address>();

		Iterator<Symbol> vftableSymbolIterator = vftableSymbols.iterator();
		while (vftableSymbolIterator.hasNext()) {
			monitor.checkCanceled();
			Symbol vftableSymbol = vftableSymbolIterator.next();
			Address vftableAddress = vftableSymbol.getAddress();
			vftableAddresses.add(vftableAddress);

			// check direct refs to see if they are in undefined area or not in function
			byte[] bytes = ProgramMemoryUtil.getDirectAddressBytes(program, vftableAddress);

			addByteSearchPattern(searcher, notInFunctionVftableRefs, newFunctions, vftableAddress,
				bytes, monitor);

		}

		searcher.search(program, searchSet, monitor);

		// check existing refs to see if in instruction but not in function
		Iterator<Address> vftableAddressIterator = vftableAddresses.iterator();
		while (vftableAddressIterator.hasNext()) {
			monitor.checkCanceled();

			Address vftableAddress = vftableAddressIterator.next();

			ReferenceIterator referencesIterator =
				program.getReferenceManager().getReferencesTo(vftableAddress);

			while (referencesIterator.hasNext()) {
				monitor.checkCanceled();

				Reference reference = referencesIterator.next();
				Address vftableReference = reference.getFromAddress();
				Function functionContaining =
					program.getListing().getFunctionContaining(vftableReference);

				if (functionContaining == null) {

					Instruction instructionContaining =
						program.getListing().getInstructionContaining(vftableReference);
					if (instructionContaining != null) {
						boolean functionCreated =
							extendedFlatAPI.createFunction(program, vftableReference);

						if (!functionCreated) {
							notInFunctionVftableRefs.add(vftableReference);

						}

					}
				}
			}
		}

		return notInFunctionVftableRefs;
	}

	/**
	 * Method to add a search pattern, to the searcher, for the set of bytes representing a vftable
	 * address
	 * @param searcher the MemoryBytePatternSearcher
	 * @param notInFunctionVftableRefs a list addresses of vftable references that are not contained
	 * in a function
	 * @param newFunctions a list of newly created functions that reference the given vftable address
	 * @param vftableAddress the given vftable address
	 * @param bytes the bytes to search for
	 * @param taskMonitor a cancellable monitor
	 */
	private void addByteSearchPattern(MemoryBytePatternSearcher searcher,
			List<Address> notInFunctionVftableRefs, List<Address> newFunctions,
			Address vftableAddress, byte[] bytes, TaskMonitor taskMonitor) {

		// no pattern bytes.
		if (bytes == null) {
			return;
		}

		// Each time a match for this byte pattern ...
		GenericMatchAction<Address> action = new GenericMatchAction<Address>(vftableAddress) {
			@Override
			public void apply(Program prog, Address addr, Match match) {

				Function functionContainingVftable = prog.getListing().getFunctionContaining(addr);

				Data dataAt = prog.getListing().getDefinedDataContaining(addr);

				Instruction instructionContainingAddr =
					prog.getListing().getInstructionContaining(addr);

				// check the direct references found with the searcher
				// if not in function but is an instruction then create the function
				// otherwise, add to the list to report to user
				if (functionContainingVftable == null && dataAt == null) {
					if (instructionContainingAddr == null) {
						notInFunctionVftableRefs.add(addr);
					}
					else {
						boolean functionCreated = extendedFlatAPI.createFunction(prog, addr);
						if (!functionCreated) {
							notInFunctionVftableRefs.add(addr);
						}

					}
				}
			}

		};

		// create a Pattern of the bytes and the MatchAction to perform upon a match
		GenericByteSequencePattern<Address> genericByteMatchPattern =
			new GenericByteSequencePattern<>(bytes, action);

		searcher.addPattern(genericByteMatchPattern);

	}

	/**
	 * Method to create a string buffer containing class parents in the correct order. The format
	 * of the parent string is of the format "class <class_name> : <parent1_spec> : <parent2_spec> ...
	 * where parentN_spec = "virtual (only if inherited virtually) <parentN_name>"
	 * Examples:
	 * The class Pet with no parents would be "class Pet"
	 * The class Cat with non-virtual parent Pet would be "class Cat : Pet"
	 * The class A with virtual parent B and non-virtual parent C would be "class A : virtual B : C"
	 * @param recoveredClass the given class
	 * @return StringBuffer containing class parent description
	 * @throws CancelledException if cancelled
	 */
	public StringBuffer createParentStringBuffer(RecoveredClass recoveredClass)
			throws CancelledException {

		StringBuffer parentStringBuffer = new StringBuffer();
		String classString = recoveredClass.getName();

		if (recoveredClass.hasParentClass()) {

			// use this to get direct  parents
			Map<RecoveredClass, List<RecoveredClass>> classHierarchyMap =
				recoveredClass.getClassHierarchyMap();
			Set<RecoveredClass> directParents = classHierarchyMap.keySet();

			// use this to get correct parent order and to get the type of parent
			Map<RecoveredClass, Boolean> parentToBaseTypeMap =
				recoveredClass.getParentToBaseTypeMap();
			Set<RecoveredClass> ancestors = parentToBaseTypeMap.keySet();
			Iterator<RecoveredClass> ancestorIterator = ancestors.iterator();
			while (ancestorIterator.hasNext()) {
				monitor.checkCanceled();
				RecoveredClass ancestor = ancestorIterator.next();
				if (directParents.contains(ancestor)) {

					Boolean isVirtualParent = parentToBaseTypeMap.get(ancestor);
					if (isVirtualParent != null && isVirtualParent) {
						classString = classString.concat(" : virtual " + ancestor.getName());
					}
					else {
						classString = classString.concat(" : " + ancestor.getName());
					}
				}
			}
		}
		parentStringBuffer.append("class " + classString);
		return parentStringBuffer;
	}

	/**
	 * Method to determine if all data for the ancestors of the given class have been created
	 * @param recoveredClass the given class
	 * @return true if all data for the ancestors of the given class have been created, false otherwise
	 * @throws CancelledException if cancelled
	 * @throws Exception if class hierarchy list has not been populated
	 */
	public boolean allAncestorDataHasBeenCreated(RecoveredClass recoveredClass)
			throws CancelledException, Exception {

		List<RecoveredClass> parentClasses = recoveredClass.getClassHierarchy();

		if (parentClasses.isEmpty()) {
			throw new Exception(recoveredClass.getClassNamespace().getName(true) +
				" should not have an empty class hierarchy");
		}

		// if size one it only includes self
		if (parentClasses.size() == 1) {
			return true;
		}

		Iterator<RecoveredClass> parentIterator = parentClasses.listIterator(1);
		while (parentIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass parentClass = parentIterator.next();

			if (getClassStructureFromDataTypeManager(parentClass) == null) {
				return false;
			}

		}
		return true;

	}

	/**
	 * Method to retrieve the given class's class structure from the data type manager
	 * @param recoveredClass the given class
	 * @return the given class's class structure from the data type manager
	 */
	public Structure getClassStructureFromDataTypeManager(RecoveredClass recoveredClass) {

		DataType classDataType =
			dataTypeManager.getDataType(recoveredClass.getClassPath(), recoveredClass.getName());

		if (classDataType != null && classDataType instanceof Structure) {
			Structure classStructure = (Structure) classDataType;
			return classStructure;
		}

		return null;

	}

	/**
	 * Method to name class constructors and add them to class namespace
	 * @param recoveredClass current class
	 * @param classStruct the given class structure
	 * @throws Exception when cancelled
	 */
	public void addConstructorsToClassNamespace(RecoveredClass recoveredClass,
			Structure classStruct) throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();
		String className = recoveredClass.getName();

		List<Function> constructorList = recoveredClass.getConstructorList();
		Iterator<Function> constructorsIterator = constructorList.iterator();

		while (constructorsIterator.hasNext()) {
			monitor.checkCanceled();
			Function constructorFunction = constructorsIterator.next();

			if (nameVfunctions) {
				createNewSymbolAtFunction(constructorFunction, className, classNamespace, true,
					true);
			}

			// if current decompiler function return type is a pointer then set the return type
			// to a pointer to the class structure, otherwise if it is a void, make it a void so the
			// listing has void too, otherwise, leave it as is, probably a void
			String returnType = getReturnTypeFromDecompiler(constructorFunction);

			// Set error bookmark, add error message, and  get the listing return type if the
			// decompiler return type is null
			if (returnType == null) {

				String msg1 = "Decompiler Error: Failed to decompile function";
				String msg2 = ", possibly due to the addition of class structure.";

				Msg.debug(this, msg1 + " at " + constructorFunction.getEntryPoint() + msg2);

				program.getBookmarkManager()
						.setBookmark(constructorFunction.getEntryPoint(), BookmarkType.ERROR,
							"Decompiler Error", msg1 + msg2);

				// get the return type from the listing and in some cases it will
				// indicate the correct type to help determine the below type to add
				returnType = constructorFunction.getReturnType().getDisplayName();
			}

			if (returnType.equals("void")) {
				DataType voidDataType = new VoidDataType();
				constructorFunction.setReturnType(voidDataType, SourceType.ANALYSIS);
			}
			else if (returnType.contains("*")) {
				DataType classPointerDataType = dataTypeManager.getPointer(classStruct);
				constructorFunction.setReturnType(classPointerDataType, SourceType.ANALYSIS);
			}
			// if neither and it is a FID function change it to undefined so the decompiler will
			// recompute it
			else if (isFidFunction(constructorFunction)) {
				DataType undefinedDT = null;
				if (defaultPointerSize == 4) {
					undefinedDT = new Undefined4DataType();
				}
				if (defaultPointerSize == 8) {
					undefinedDT = new Undefined8DataType();
				}
				if (undefinedDT != null) {
					constructorFunction.setReturnType(undefinedDT, SourceType.ANALYSIS);
				}
			}

		}
	}

	/**
	 * Get the return value from the decompiler signature for the given function
	 * @param function the given function
	 * @return the decompiler return value for the given function
	 */
	private String getReturnTypeFromDecompiler(Function function) {

		DataType decompilerReturnType = decompilerUtils.getDecompilerReturnType(function);

		if (decompilerReturnType == null) {
			return null;
		}

		return decompilerReturnType.getDisplayName();
	}

	/**
	 * Method to name class destructors and add them to class namespace
	 * @param recoveredClass current class
	 * @param classStruct the class structure for the given class
	 * @throws Exception when cancelled
	 */
	public void addDestructorsToClassNamespace(RecoveredClass recoveredClass, Structure classStruct)
			throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();
		String className = recoveredClass.getName();

		List<Function> destructorList = recoveredClass.getDestructorList();
		Iterator<Function> destructorIterator = destructorList.iterator();
		while (destructorIterator.hasNext()) {
			monitor.checkCanceled();
			Function destructorFunction = destructorIterator.next();
			String destructorName = "~" + className;

			if (nameVfunctions) {
				createNewSymbolAtFunction(destructorFunction, destructorName, classNamespace, true,
					true);
			}

			destructorFunction.setReturnType(new VoidDataType(), SourceType.ANALYSIS);
		}
	}

	/**
	 * Method to name non-this destructors and add them to class namespace
	 * @param recoveredClass current class
	 * @throws Exception when cancelled
	 */
	public void addNonThisDestructorsToClassNamespace(RecoveredClass recoveredClass)
			throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();
		String className = recoveredClass.getName();

		List<Function> nonThisDestructorList = recoveredClass.getNonThisDestructors();
		Iterator<Function> destructorIterator = nonThisDestructorList.iterator();
		while (destructorIterator.hasNext()) {
			monitor.checkCanceled();
			Function destructorFunction = destructorIterator.next();
			String destructorName = "~" + className;

			createNewSymbolAtFunction(destructorFunction, destructorName, classNamespace, false,
				false);
		}
	}

	/**
	 * Method to name class vbase destructors and add them to class namespace
	 * @param recoveredClass current class
	 * @param classStruct the class structure for the given class
	 * @throws Exception when cancelled
	 */
	public void addVbaseDestructorsToClassNamespace(RecoveredClass recoveredClass,
			Structure classStruct) throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();

		Function vbaseDestructorFunction = recoveredClass.getVBaseDestructor();
		if (vbaseDestructorFunction != null) {
			String destructorName = VBASE_DESTRUCTOR_LABEL;

			if (nameVfunctions) {
				createNewSymbolAtFunction(vbaseDestructorFunction, destructorName, classNamespace,
					true, true);
			}

			vbaseDestructorFunction.setReturnType(new VoidDataType(), SourceType.ANALYSIS);
		}

	}

	/**
	 * Method to name the class vbtable, if one exists, and add it to the class namespace
	 * @param recoveredClass the given class
	 * @throws Exception if exception thrown
	 */
	public void addVbtableToClassNamespace(RecoveredClass recoveredClass) throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();

		Address vbtableAddress = recoveredClass.getVbtableAddress();

		if (vbtableAddress == null) {
			return;
		}

		createNewSymbolAtAddress(vbtableAddress, VBTABLE_LABEL, classNamespace);

	}

	/**
	 * Method to create a new symbol at an address
	 * @param address the given address
	 * @param name the name to give the new symbol
	 * @param namespace the namespace to put the new symbol in
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CancelledException if cancelled
	 */
	public void createNewSymbolAtAddress(Address address, String name, Namespace namespace)
			throws DuplicateNameException, InvalidInputException, CircularDependencyException,
			CancelledException {

		Symbol symbol = symbolTable.getSymbol(name, address, namespace);

		// already exists
		if (symbol != null) {
			return;
		}

		// check to see if symbol is same name but in global namespace
		List<Symbol> symbolsByNameAtAddress = getSymbolsByNameAtAddress(address, name);

		// if no same name symbol, add new symbol
		if (symbolsByNameAtAddress.size() == 0) {
			AddLabelCmd lcmd = new AddLabelCmd(address, name, namespace, SourceType.ANALYSIS);
			if (!lcmd.applyTo(program)) {
				if (DEBUG) {
					Msg.debug(this,
						"ERROR: Could not add new symbol " + name + " to " + address.toString());
				}
			}
		}
		//put the same name one in the namespace
		else {
			Iterator<Symbol> iterator = symbolsByNameAtAddress.iterator();
			while (iterator.hasNext()) {
				monitor.checkCanceled();
				Symbol sameNameSymbol = iterator.next();
				sameNameSymbol.setNamespace(namespace);
			}
		}

		return;
	}

	/**
	 * Method to replace the program's current class structure, only if an empty placeholder structure,
	 * with the one generated by this script
	 * @param function a class method with current class structure applied
	 * @param className the given class name
	 * @param newClassStructure the new structure to replace the old with
	 * @throws DataTypeDependencyException if there is a data dependency exception when replacing
	 */
	public void replaceEmptyClassStructure(Function function, String className,
			Structure newClassStructure) throws DataTypeDependencyException {

		Parameter thisParam = function.getParameter(0);
		if (thisParam == null) {
			return;
		}

		DataType dataType = thisParam.getDataType();
		if (dataType instanceof Pointer) {
			Pointer ptr = (Pointer) dataType;
			DataType baseDataType = ptr.getDataType();
			if (baseDataType.getName().equals(className) && baseDataType.isNotYetDefined()) {

				dataTypeManager.replaceDataType(baseDataType, newClassStructure, false);

				// remove original folder if it is empty after the replace
				CategoryPath originalPath = baseDataType.getCategoryPath();
				Category category = dataTypeManager.getCategory(originalPath);
				Category parentCategory = category.getParent();
				if (parentCategory != null) {
					parentCategory.removeEmptyCategory(category.getName(), monitor);
				}

			}
		}
	}


	/**
	 * Method to create a new symbol at the given function
	 * @param function the given function
	 * @param name the name for the new symbol
	 * @param namespace the namespace to put the new symbol in
	 * @param setPrimary if true, set the new symbol primary, if false do not make the new symbol primary
	 * @param removeBadFID if true, check for and remove any incorrect FID symbols, if false leave them there
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CancelledException if cancelled
	 */
	private void createNewSymbolAtFunction(Function function, String name, Namespace namespace,
			boolean setPrimary, boolean removeBadFID) throws DuplicateNameException,
			InvalidInputException, CircularDependencyException, CancelledException {

		// check for bad FID or FID that needs fix up and remove those bad symbols
		if (removeBadFID) {
			removeBadFIDSymbols(namespace, name, function);
		}

		if (function.equals(purecall)) {
			return;
		}

		Symbol symbol = symbolTable.getSymbol(name, function.getEntryPoint(), namespace);

		// already exists
		if (symbol != null) {
			return;
		}

		// check to see if symbol is same name but in global namespace
		List<Symbol> symbolsByNameAtAddress =
			getSymbolsByNameAtAddress(function.getEntryPoint(), name);

		// if no same name symbol, add new symbol
		if (symbolsByNameAtAddress.size() == 0) {
			AddLabelCmd lcmd =
				new AddLabelCmd(function.getEntryPoint(), name, namespace, SourceType.ANALYSIS);
			if (!lcmd.applyTo(program)) {
				if (DEBUG) {
					Msg.debug(this, "ERROR: Could not add new function label " + name + " to " +
						function.getEntryPoint().toString());
				}
				return;
			}

			symbol = lcmd.getSymbol();
			if (setPrimary && !symbol.isPrimary()) {
				SetLabelPrimaryCmd scmd =
					new SetLabelPrimaryCmd(function.getEntryPoint(), name, namespace);
				if (!scmd.applyTo(program)) {
					if (DEBUG) {
						Msg.debug(this, "ERROR: Could not make function label " + name +
							" primary at " + function.getEntryPoint().toString());
					}
				}
			}
		}
		//put the same name one in the namespace
		else {
			Iterator<Symbol> iterator = symbolsByNameAtAddress.iterator();
			while (iterator.hasNext()) {
				monitor.checkCanceled();
				Symbol sameNameSymbol = iterator.next();
				sameNameSymbol.setNamespace(namespace);
			}
		}

		return;
	}

	/**
	 * Method to remove the primary label, applied by FID analyzer,  at the given address if it does not match the given name
	 * leave the secondary labels alone, ie the mangled name, so people can regenerate the old symbol
	 * if they want to
	 * @param namespace the given namespace
	 * @param name the given name
	 * @param function the given function
	 * @throws CancelledException if cancelled
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if issues setting return type
	 */
	private void removeBadFIDSymbols(Namespace namespace, String name, Function function)
			throws CancelledException, InvalidInputException, DuplicateNameException,
			CircularDependencyException {

		Address functionAddress = function.getEntryPoint();

		BookmarkManager bm = program.getBookmarkManager();

		Bookmark bookmark =
			bm.getBookmark(functionAddress, BookmarkType.ANALYSIS, "Function ID Analyzer");

		if (bookmark == null) {
			return;
		}

		String bookmarkComment = bookmark.getComment();

		// just get primary symbol and check it - if no match then remove all symbols and replace with good one
		if (bookmarkComment.contains("Single Match")) {

			Symbol symbol = symbolTable.getPrimarySymbol(functionAddress);

			if (symbol != null && symbol.getSource() == SourceType.ANALYSIS &&
				!symbol.getName().equals(name) && !symbol.getParentNamespace().equals(namespace)) {

				// add to list of bad namespaces to be cleaned up later
				Namespace parentNamespace = symbol.getParentNamespace();
				if (!parentNamespace.isGlobal()) {
					badFIDNamespaces.add(parentNamespace);
				}
				extendedFlatAPI.addUniqueStringToPlateComment(functionAddress,
					"***** Removed Bad FID Symbol *****");

				if (!badFIDFunctions.contains(function)) {
					badFIDFunctions.add(function);
				}

				findAndRemoveBadStructuresFromFunction(function, namespace);
				extendedFlatAPI.removeAllSymbolsAtAddress(functionAddress);

			}
			return;
		}
		// FID with multiple matches - either all FID_conflicts or one common name
		// since no good namespace all need to be removed but if there is a good base name
		// add FID
		if (bookmarkComment.contains("Multiple Matches")) {
			// See if any contain the class name and if so add "resolved" and if not
			if (doAnySymbolsHaveMatchingName(functionAddress, name)) {
				extendedFlatAPI.addUniqueStringToPlateComment(functionAddress,
					"***** Resolved FID Conflict *****");

				if (!resolvedFIDFunctions.contains(function)) {
					resolvedFIDFunctions.add(function);
				}

				findAndRemoveBadStructuresFromFunction(function, namespace);
			}
			else {
				extendedFlatAPI.addUniqueStringToPlateComment(functionAddress,
					"***** Removed Bad FID Symbol(s) *****");

				if (!badFIDFunctions.contains(function)) {
					badFIDFunctions.add(function);
				}

				findAndRemoveBadStructuresFromFunction(function, namespace);

			}
			extendedFlatAPI.removeAllSymbolsAtAddress(functionAddress);
			return;
		}
	}

	/**
	* Method to find empty structures that were created when incorrect FID function
	* signatures were applied and remove them from the given function. Incorrect structure
	* data types are added to a global list so that if nothing remains that references the data
	* type, it can be removed after all functions have been processed.
	* @param function the function with incorrect FID signature
	* @param namespace the correct namespace of the function
	* @throws CancelledException if cancelled
	* @throws InvalidInputException if error setting return type
	* @throws DuplicateNameException if try to create same symbol name already in namespace
	* @throws CircularDependencyException if parent namespace is descendent of given namespace
	*/
	private void findAndRemoveBadStructuresFromFunction(Function function, Namespace namespace)
			throws CancelledException, InvalidInputException, DuplicateNameException,
			CircularDependencyException {

		// find bad structure parameter data types
		List<Structure> badStructureDataTypes = findBadParameterDataTypes(function, namespace);

		// find bad structure return data types
		Structure badReturnType = findBadReturnType(function, namespace);
		if (badReturnType != null && !badStructureDataTypes.contains(badReturnType)) {
			badStructureDataTypes.add(badReturnType);
		}

		// if bad structures were found delete and recreate the function and all calling functions
		// in order to remove the bad data types from the function signature
		if (badStructureDataTypes.size() > 0) {
			// find all functions that call this function and do the same
			fixBadSignatures(function, badStructureDataTypes);
			// add all the new bad dts to the list of bad ones
			Iterator<Structure> badStructuresIterator = badStructureDataTypes.iterator();
			while (badStructuresIterator.hasNext()) {
				monitor.checkCanceled();
				Structure structure = badStructuresIterator.next();
				if (!badFIDStructures.contains(structure)) {
					badFIDStructures.add(structure);
				}
			}
		}
	}

	/**
	 * Method to remove incorrect data types from the given function's signature and from
	 * all calling functions
	 * @param function function with bad signature
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if invalid data input
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	private void fixBadSignatures(Function function, List<Structure> badStructureDataTypes)
			throws CancelledException, InvalidInputException, DuplicateNameException,
			CircularDependencyException {

		List<Function> allFunctionsToFix = new ArrayList<Function>();
		allFunctionsToFix.add(function);
		Set<Function> callingFunctions = function.getCallingFunctions(monitor);

		while (callingFunctions != null && !callingFunctions.isEmpty()) {
			monitor.checkCanceled();
			List<Function> moreCallingFunctions = new ArrayList<Function>();
			Iterator<Function> callingFunctionsIterator = callingFunctions.iterator();
			while (callingFunctionsIterator.hasNext()) {
				monitor.checkCanceled();
				Function callingFunction = callingFunctionsIterator.next();
				if (!allFunctionsToFix.contains(callingFunction)) {
					allFunctionsToFix.add(callingFunction);
					moreCallingFunctions.addAll(callingFunction.getCallingFunctions(monitor));
				}
				callingFunctionsIterator.remove();
			}
			callingFunctions.addAll(moreCallingFunctions);
		}

		Iterator<Function> functionsToFixIterator = allFunctionsToFix.iterator();
		while (functionsToFixIterator.hasNext()) {
			monitor.checkCanceled();
			Function functionToFix = functionsToFixIterator.next();
			if (!functionToFix.isThunk()) {

				removeBadReturnType(functionToFix, badStructureDataTypes);
				removeBadParameterDataTypes(functionToFix, badStructureDataTypes);

				if (!fixedFIDFunctions.contains(functionToFix)) {
					fixedFIDFunctions.add(functionToFix);
				}
			}
		}

	}

	/**
	 * Method to find and add to permanent removal list any incorrect empty structure params
	 * @param function the function to check for bad params
	 * @param namespace the correct parent namespace of function
	 * @throws CancelledException when cancelled
	 */
	private List<Structure> findBadParameterDataTypes(Function function, Namespace namespace)
			throws CancelledException {

		List<Structure> badStructureDataTypes = new ArrayList<Structure>();

		int parameterCount = function.getParameterCount();
		for (int i = 0; i < parameterCount; i++) {
			monitor.checkCanceled();
			DataType dataType = function.getParameter(i).getDataType();
			if (!dataType.getName().equals(namespace.getName()) &&
				extendedFlatAPI.isPointerToEmptyStructure(dataType)) {
				Pointer ptr = (Pointer) dataType;
				Structure structure = (Structure) ptr.getDataType();

				if (!badStructureDataTypes.contains(structure)) {
					badStructureDataTypes.add(structure);
				}
			}
		}
		return badStructureDataTypes;
	}

	/**
	 * Method to replace the given bad structure data types with undefined data types of same size
	 * for the given functions parameters
	 * @param function the function to fix
	 * @param badStructureDataTypes the list of bad structure data types to replace if found
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if invalid data input
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	private void removeBadParameterDataTypes(Function function,
			List<Structure> badStructureDataTypes) throws CancelledException,
			DuplicateNameException, InvalidInputException, CircularDependencyException {

		int parameterCount = function.getParameterCount();
		for (int i = 0; i < parameterCount; i++) {
			monitor.checkCanceled();
			DataType paramDataType = function.getParameter(i).getDataType();
			Structure baseDataType = extendedFlatAPI.getBaseStructureDataType(paramDataType);
			if (baseDataType != null && badStructureDataTypes.contains(baseDataType)) {

				// To remove from this param we have to remove the function from its namespace
				if (function.getParameter(i).getName().equals("this")) {
					function.setParentNamespace(globalNamespace);

				}
				else {
					PointerDataType ptrUndefined =
						extendedFlatAPI.createPointerToUndefinedDataType(paramDataType);
					if (ptrUndefined != null) {
						function.getParameter(i).setDataType(ptrUndefined, SourceType.ANALYSIS);
					}

					else {
						if (DEBUG) {
							Msg.debug(this, "ERROR: " + function.getEntryPoint().toString() +
								" Could not replace parameter " + i + " with undefined pointer.");
						}
					}
				}
			}
		}
	}

	/**
	 * Method to find incorrect empty structure return type
	 * @param function the function to check
	 * @param namespace the parent namespace of the function
	 */
	private Structure findBadReturnType(Function function, Namespace namespace) {

		DataType returnType = function.getReturnType();
		if (!returnType.getName().equals(namespace.getName()) &&
			extendedFlatAPI.isPointerToEmptyStructure(returnType)) {
			Pointer ptr = (Pointer) returnType;
			Structure structure = (Structure) ptr.getDataType();

			return structure;

		}
		return null;
	}

	/**
	 * Method to fix a bad return type if it is one of the bad structure data types on the given
	 * list. The list was previously generated from functions that had incorrect FID signatures
	 * placed on them that this script recognized and corrected.
	 * @param function the given function
	 * @param badStructureDataTypes a list of bad structure data types
	 * @throws InvalidInputException if issue setting return type
	 */
	private void removeBadReturnType(Function function, List<Structure> badStructureDataTypes)
			throws InvalidInputException {

		DataType returnType = function.getReturnType();
		Structure baseDataType = extendedFlatAPI.getBaseStructureDataType(returnType);
		if (baseDataType != null && badStructureDataTypes.contains(baseDataType)) {
			PointerDataType ptrUndefined =
				extendedFlatAPI.createPointerToUndefinedDataType(returnType);
			if (ptrUndefined != null) {
				function.setReturnType(ptrUndefined, SourceType.ANALYSIS);
			}
		}
	}

	/**
	 * Method to determine if any symbols at the given address have matching names
	 * as the given name after removing template, pdb quotes, or FID_conflict characters
	 * added by other analyzers.
	 * @param address the given address
	 * @param name the name to match
	 * @return true if any symbols at the given address "match" the given name, false otherwise
	 * @throws CancelledException when canceled
	 */
	private boolean doAnySymbolsHaveMatchingName(Address address, String name)
			throws CancelledException {

		String simpleName = extendedFlatAPI.removeTemplate(name);

		SymbolIterator it = symbolTable.getSymbolsAsIterator(address);
		for (Symbol symbol : it) {
			monitor.checkCanceled();

			String simpleSymbolName = extendedFlatAPI.removeTemplate(symbol.getName());
			simpleSymbolName = removeSingleQuotes(simpleSymbolName);
			simpleSymbolName = removeFIDConflict(simpleSymbolName);
			simpleSymbolName = removeSingleQuotes(simpleSymbolName);

			if (simpleName.equals(simpleSymbolName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method to remove single quotes from beginning and end of given string
	 * @param string string to process
	 * @return string without leading or trailing single quotes
	 */
	private String removeSingleQuotes(String string) {
		if (string.startsWith("`")) {
			string = string.substring(1);
		}
		if (string.endsWith("'")) {
			string = string.substring(0, string.length() - 1);
		}
		return string;

	}

	/**
	 * Method to remove "FID_conflict:" prefix from the given string
	 * @param string string to process
	 * @return string without "FID_conflict:" prefix
	 */
	private String removeFIDConflict(String string) {
		if (string.startsWith("FID_conflict:")) {
			string = string.substring(13);
		}
		return string;

	}

	/**
	 * Method to get symbols with the given name at the given address
	 * @param address the given address
	 * @param name the given name to match
	 * @return a list of symbols with the given name at the given address
	 * @throws CancelledException if cancelled
	 */
	List<Symbol> getSymbolsByNameAtAddress(Address address, String name) throws CancelledException {

		List<Symbol> sameNameSymbols = new ArrayList<Symbol>();

		Symbol[] symbols = symbolTable.getSymbols(address);
		for (Symbol symbol : symbols) {
			monitor.checkCanceled();
			Namespace namespace = symbol.getParentNamespace();

			if (namespace.isGlobal() && symbol.getName().equals(name)) {
				sameNameSymbols.add(symbol);
			}
			else if (namespace.isGlobal() && name.equals(DELETING_DESTRUCTOR_LABEL) &&
				symbol.getName().contains(DELETING_DESTRUCTOR_LABEL)) {
				sameNameSymbols.add(symbol);
			}
		}
		return sameNameSymbols;
	}

	/**
	 * Returns a new address with the specified offset in the default address space.
	 * @param offset the offset for the new address
	 * @return a new address with the specified offset in the default address space
	 */
	public final Address toAddr(long offset) {
		return program.getAddressFactory().getDefaultAddressSpace().getAddress(offset);
	}

	/**
	 * Method to determine if the given constructor function calls any non-constructors 
	 * before the vftable refererence
	 * @param recoveredClass the given class
	 * @param constructor the given constructor function
	 * @param vftableReference the address of the reference to the class vftable 
	 * @return true if the given constructor function calls any non-constructors before the 
	 * vftable refererence, false otherwise
	 * @throws CancelledException if cancelled
	 */
	public boolean doesFunctionCallAnyNonConstructorsBeforeVtableReference(
			RecoveredClass recoveredClass, Function constructor, Address vftableReference)
			throws CancelledException {

		InstructionIterator instructions =
			constructor.getProgram().getListing().getInstructions(constructor.getBody(), true);

		while (instructions.hasNext()) {
			monitor.checkCanceled();
			Instruction instruction = instructions.next();
			Address instructionAddress = instruction.getAddress();
			if (instructionAddress.compareTo(vftableReference) >= 0) {
				return false;
			}
			if (instruction.getFlowType().isCall()) {

				Function calledFunction =
					extendedFlatAPI.getReferencedFunction(instruction.getMinAddress(), true);

				if (calledFunction == null) {
					return true;
				}

				if (calledFunction.getName().contains("prolog")) {
					continue;
				}

				if (!getAllConstructors().contains(calledFunction)) {
					return true;
				}
			}

		}

		return false;

	}

	/**
	 * Method to find basic clone functions from given classes virtual functions
	 * @param recoveredClasses list of classes to process
	 * @throws CancelledException if cancelled
	 */
	public void findBasicCloneFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		operatorNewFunctions = getOperatorNewFunctions(recoveredClasses);

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();
		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();

			RecoveredClass recoveredClass = recoveredClassIterator.next();

			List<Function> constructorList = recoveredClass.getConstructorList();
			List<Function> allVirtualFunctions = recoveredClass.getAllVirtualFunctions();

			if (allVirtualFunctions.isEmpty()) {
				continue;
			}
			for (Function vfunction : allVirtualFunctions) {

				monitor.checkCanceled();

				if (!hasNCalls(vfunction, 2)) {
					continue;
				}

				Function firstCalledFunction =
					extendedFlatAPI.getCalledFunctionByCallOrder(vfunction, 1, false);

				// skip computed calls (ie call eax)
				if (firstCalledFunction == null) {
					continue;
				}

				if (!operatorNewFunctions.contains(firstCalledFunction)) {
					continue;
				}

				// not a clone if the second called function isn't a class constructor
				Function secondFunction =
					extendedFlatAPI.getCalledFunctionByCallOrder(vfunction, 2, true);

				if (secondFunction == null) {
					continue;
				}

				if (!constructorList.contains(secondFunction)) {
					continue;
				}

				recoveredClass.addCloneFunction(vfunction);

			}
		}

	}

	private boolean hasNCalls(Function function, int n) throws CancelledException {

		InstructionIterator instructions =
			function.getProgram().getListing().getInstructions(function.getBody(), true);

		int numCalls = 0;
		while (instructions.hasNext()) {
			monitor.checkCanceled();
			Instruction instruction = instructions.next();
			if (instruction.getFlowType().isCall()) {
				numCalls++;
				if (numCalls > n) {
					return false;
				}
			}
		}
		if (numCalls == n) {
			return true;
		}
		return false;
	}

	/**
	 * Method to remove the empty namespaces and unreferenced empty class structures that
	 *  that were incorrectly applied by FID
	 * @throws CancelledException when script is cancelled
	 */
	public void removeEmptyClassesAndStructures() throws CancelledException {

		Iterator<Namespace> badNamespaceIterator = badFIDNamespaces.iterator();
		while (badNamespaceIterator.hasNext()) {
			monitor.checkCanceled();
			Namespace badNamespace = badNamespaceIterator.next();

			// global namespace shouldn't be on list but check anyway
			if (badNamespace.isGlobal()) {
				continue;
			}

			// delete empty namespace and parent namespaces
			if (!extendedFlatAPI.hasSymbolsInNamespace(badNamespace)) {
				removeEmptyNamespaces(badNamespace);
			}
		}

		// remove unused empty structures
		removeEmptyStructures();

	}

	/**
	 * Method to remove the given namespace if it is empty and its parent namepaces if they are empty
	 * @param namespace the given namespace
	 * @throws CancelledException if cancelled
	 */
	private void removeEmptyNamespaces(Namespace namespace) throws CancelledException {

		// delete empty namespace and parent namespaces
		Namespace parentNamespace = namespace.getParentNamespace();

		namespace.getSymbol().delete();
		while (parentNamespace != null && !extendedFlatAPI.hasSymbolsInNamespace(parentNamespace)) {
			monitor.checkCanceled();

			namespace = parentNamespace;
			parentNamespace = parentNamespace.getParentNamespace();
			namespace.getSymbol().delete();
		}
	}

	/**
	 * Method to remove the incorrectly applied and unreferenced empty structures that are not used
	 * @throws CancelledException when script is cancelled
	 */
	private void removeEmptyStructures() throws CancelledException {

		Iterator<Structure> badStructureIterator = badFIDStructures.iterator();
		while (badStructureIterator.hasNext()) {

			monitor.checkCanceled();

			// if not used by anything remove it
			Structure badStructure = badStructureIterator.next();
			ListAccumulator<LocationReference> accumulator = new ListAccumulator<>();

			boolean discoverTypes = true;
			ReferenceUtils.findDataTypeReferences(accumulator, badStructure, program, discoverTypes,
				monitor);

			List<LocationReference> referenceList = accumulator.asList();
			if (referenceList.isEmpty()) {
				// delete empty class data type and empty parent folders
				removeEmptyStructure(badStructure.getDataTypePath().getCategoryPath(),
					badStructure.getName());
			}
		}
	}

	/**
	 * Method to remove the structure with the given folder path and name if it is empty
	 * @param folderPath the given folder path in the data type manager
	 * @param structureName the given structure name
	 * @throws CancelledException if cancelled
	 */
	private void removeEmptyStructure(CategoryPath folderPath, String structureName)
			throws CancelledException {

		DataType dataType = dataTypeManager.getDataType(folderPath, structureName);
		if (extendedFlatAPI.isEmptyStructure(dataType)) {

			dataTypeManager.remove(dataType, monitor);
			Category classCategory = dataTypeManager.getCategory(folderPath);
			Category parentCategory = classCategory.getParent();
			boolean tryToRemove = true;
			while (parentCategory != null && tryToRemove) {
				monitor.checkCanceled();

				tryToRemove = parentCategory.removeEmptyCategory(classCategory.getName(), monitor);
				classCategory = parentCategory;
				parentCategory = parentCategory.getParent();
			}

		}

	}

	/**
	 * Method to create empty vftable structures before class struct is created so that
	 * they can be added to the class structure. Afterwords, they are filled in with pointers
	 * to vftable functions
	 * @param recoveredClass the given class
	 * @return Map of address/vftable structure pointers
	 * @throws Exception when invalid data creation
	 */
	public Map<Address, DataType> createEmptyVfTableStructs(RecoveredClass recoveredClass)
			throws Exception {

		Map<Address, DataType> vftableToStructureMap = new HashMap<Address, DataType>();

		String className = recoveredClass.getName();

		CategoryPath classPath = recoveredClass.getClassPath();

		Structure vftableStruct = null;

		Map<Integer, Address> orderToVftableMap = recoveredClass.getOrderToVftableMap();

		for (int index = 0; index < orderToVftableMap.size(); index++) {

			monitor.checkCanceled();

			Address vftableAddress = orderToVftableMap.get(index);

			// if only one vftable name the structure <class_name>_vftable
			if (orderToVftableMap.size() == 1) {
				vftableStruct = new StructureDataType(classPath,
					className + CLASS_VFUNCTION_STRUCT_NAME, 0, dataTypeManager);
			}
			// if more than one, name it <class_name>_vftable_for_<associated_parent_name> if
			// can associate them or <class_name_vftable_<vftable_order> if can't assoc parent
			else {
				RecoveredClass vftableParentClass =
					recoveredClass.getVftableBaseClass(vftableAddress);
				// should never happen but just in case
				if (vftableParentClass == null) {
					vftableStruct = new StructureDataType(classPath,
						className + CLASS_VFUNCTION_STRUCT_NAME + index, 0, dataTypeManager);
				}
				else {
					vftableStruct =
						new StructureDataType(classPath, className + CLASS_VFUNCTION_STRUCT_NAME +
							"_for_" + vftableParentClass.getName(), 0, dataTypeManager);
				}
			}

			// pack the structure then add it to the data type manager
			vftableStruct.setPackingEnabled(true);
			vftableStruct = (Structure) dataTypeManager.addDataType(vftableStruct,
				DataTypeConflictHandler.DEFAULT_HANDLER);

			DataType vfPointerDataType = dataTypeManager.getPointer(vftableStruct);

			vftableToStructureMap.put(vftableAddress, vfPointerDataType);
		}
		return vftableToStructureMap;
	}

	/**
	 * Method to create class structure for single inheritance, no parent, non-vftable classes
	 * @param recoveredClass the given class
	 * @throws CancelledException when cancelled
	 */
	public void createClassStructureWhenNoParentOrVftable(RecoveredClass recoveredClass)
			throws CancelledException {

		Structure classStruct;
		if (recoveredClass.hasExistingClassStructure()) {
			Structure computedClassDataStructure = recoveredClass.getExistingClassStructure();
			int structLen = 0;
			if (computedClassDataStructure != null) {
				structLen = computedClassDataStructure.getLength();
				int mod = structLen % defaultPointerSize;
				int alignment = 0;
				if (mod != 0) {
					alignment = defaultPointerSize - mod;
					structLen += alignment;
				}

				classStruct = new StructureDataType(recoveredClass.getClassPath(),
					recoveredClass.getName(), structLen, dataTypeManager);

				int numComponents = computedClassDataStructure.getNumDefinedComponents();
				for (int i = 1; i < numComponents; i++) {
					monitor.checkCanceled();
					DataTypeComponent component = computedClassDataStructure.getComponent(i);
					int offset = component.getOffset();
					classStruct.replaceAtOffset(offset, component.getDataType(),
						component.getDataType().getLength(), component.getFieldName(),
						component.getComment());
				}
			}
			else {
				classStruct = new StructureDataType(recoveredClass.getClassPath(),
					recoveredClass.getName(), defaultPointerSize, dataTypeManager);
			}

		}
		// make it default ptr size so it aligns inside child class correctly
		else {
			classStruct = new StructureDataType(recoveredClass.getClassPath(),
				recoveredClass.getName(), defaultPointerSize, dataTypeManager);
		}

		// create a description indicating class parentage
		classStruct.setDescription(createParentStringBuffer(recoveredClass).toString());

		classStruct = (Structure) dataTypeManager.addDataType(classStruct,
			DataTypeConflictHandler.DEFAULT_HANDLER);

	}

	/**
	 * Method to fill in the vftable structure with pointers to virtual function signature data types
	 * @param recoveredClass the current class to be processed
	 * @param vftableToStructureMap the map from the class's vftables to the correct vftable structure data type
	 * @param classStruct the class structure for the given class
	 * @throws CancelledException when cancelled
	 * @throws Exception if other exception
	 */
	public void fillInAndApplyVftableStructAndNameVfunctions(RecoveredClass recoveredClass,
			Map<Address, DataType> vftableToStructureMap, Structure classStruct)
			throws CancelledException, Exception {

		//create function definition for each virtual function and put in vftable structure and
		// data subfolder
		CategoryPath classPath = recoveredClass.getClassPath();

		List<Address> vftableAddresses = recoveredClass.getVftableAddresses();
		Iterator<Address> vftableAddressIterator = vftableAddresses.iterator();

		while (vftableAddressIterator.hasNext()) {
			monitor.checkCanceled();
			Address vftableAddress = vftableAddressIterator.next();

			PointerDataType vftablePointerDataType =
				(PointerDataType) vftableToStructureMap.get(vftableAddress);

			DataType vftableDataType = vftablePointerDataType.getDataType();

			String vftableStructureName = vftableDataType.getName();
			vftableDataType = dataTypeManager.getDataType(vftableDataType.getCategoryPath(),
				vftableStructureName);

			Structure vftableStruct = (Structure) vftableDataType;

			if (nameVfunctions) {
				// if no pdb info, name all the vfunctions for this vftable and put in class namespace
				nameVfunctions(recoveredClass, vftableAddress, vftableStructureName);
			}

			List<Function> vFunctions = recoveredClass.getVirtualFunctions(vftableAddress);
			int vfunctionNumber = 1;
			Iterator<Function> vfIterator = vFunctions.iterator();

			while (vfIterator.hasNext()) {

				monitor.checkCanceled();
				Function vfunction = vfIterator.next();

				if (vfunction == null) {
					Pointer nullPointer = dataTypeManager.getPointer(DataType.DEFAULT);
					vftableStruct.add(nullPointer, "null pointer", null);
					continue;
				}

				String forClassSuffix = getForClassSuffix(vftableStructureName);
				String functionDefName = vfunction.getName();
				int indexOfSuffix = functionDefName.indexOf(forClassSuffix);

				// if vfunction name has the "for_parent" suffix, strip it off for the function definition name
				if (indexOfSuffix > 0) {
					functionDefName = functionDefName.substring(0, indexOfSuffix);

				}

				// get the classPath of highest level parent with vfAddress in their vftable
				classPath = getCategoryPathForFunctionSignature(vfunction, functionDefName,
					recoveredClass, vftableAddress);

				Symbol vfunctionSymbol = symbolTable.getPrimarySymbol(vfunction.getEntryPoint());
				Namespace parentNamespace = vfunctionSymbol.getParentNamespace();

				String classCommentPrefix = "";

				if (!parentNamespace.equals(globalNamespace)) {
					RecoveredClass vfunctionClass = getClass(parentNamespace);

					// this is null when there is a class from somewhere other than RTTI so it is
					// not stored in the map. Just use the parent namespace name in this case
					if (vfunctionClass == null) {
						classCommentPrefix = parentNamespace.getName();
					}
					else if (vfunctionClass.getShortenedTemplateName() != null &&
						useShortTemplates && !vfunctionClass.getShortenedTemplateName().isEmpty()) {
						classCommentPrefix = vfunctionClass.getShortenedTemplateName();
					}
					else {
						classCommentPrefix = vfunctionClass.getName();
					}

				}

				// Create comment to indicate it is a virtual function and which number in the table
				String comment = VFUNCTION_COMMENT + vfunctionNumber;

				// add comment suffix for multi classes to distinguish which vftable it is for

				if (!forClassSuffix.isEmpty()) {

					int index = forClassSuffix.indexOf("for_");
					String commentSuffix = "";
					if (index > 0) {
						commentSuffix = " for parent class " + forClassSuffix.substring(index + 4);
					}
					comment = comment + commentSuffix;
				}

				// if function is "purecall" function make the class field name "vfunction #n" instead
				// of using the function name of "purecall" and prepend "pure" to the comment so
				// they know it is pure virtual function, ie not actually implemented in the parent class
				String nameField = vfunction.getName();

				FunctionDefinition functionDataType =
					new FunctionDefinitionDataType(vfunction, true);

				if (!vfunction.getName().equals(functionDefName)) {
					functionDataType.setName(functionDefName);
				}

				functionDataType.setReturnType(vfunction.getReturnType());

				// if the function is a purecall need to create the function definition using
				// the equivalent child virtual function signature
				if (nameField.contains("purecall")) {

					nameField = DEFAULT_VFUNCTION_PREFIX + vfunctionNumber;

					// get function sig from child class
					Function childVirtualFunction =
						getChildVirtualFunction(recoveredClass, vftableAddress, vfunctionNumber);
					if (childVirtualFunction != null) {
						functionDataType =
							new FunctionDefinitionDataType(childVirtualFunction, true);
						functionDataType.setReturnType(childVirtualFunction.getReturnType());
						Symbol childFunctionSymbol =
							symbolTable.getPrimarySymbol(childVirtualFunction.getEntryPoint());

						// if the child function has a default name, rename the function definition
						// data type to the "vfunction<vfunctionNumber>" name
						if (childFunctionSymbol.getSource() == SourceType.DEFAULT) {
							functionDataType.setName(nameField);
						}
					}
					comment = recoveredClass.getName() + " pure " + comment;

				}

				PointerDataType functionPointerDataType =
					createFunctionSignaturePointerDataType(functionDataType, classPath);

				vftableStruct.add(functionPointerDataType, nameField,
					classCommentPrefix + " " + comment);
				vfunctionNumber++;
			}

			// align the structure then add it to the data type manager
			vftableStruct.setPackingEnabled(true);
			vftableStruct = (Structure) dataTypeManager.addDataType(vftableStruct,
				DataTypeConflictHandler.DEFAULT_HANDLER);

			// clear the array or unprocessed structure at the current vftable location and
			// apply the structure. It has to be one or the other and the correct length
			// because of the check at the beginning of the script that checked for either
			// array or structure of pointers and got size from them initially
			api.clearListing(vftableAddress);
			api.createData(vftableAddress, vftableStruct);

		}
	}

	/**
	 * Method to get a child class virtual function at the given offset into the correct virtual function table
	 * @param recoveredClass the given class
	 * @param virtualFunctionNumber the virtual function offset into the table
	 * @return a child class virtual function at the given offset
	 */
	private Function getChildVirtualFunction(RecoveredClass recoveredClass, Address vftableAddress,
			int virtualFunctionNumber) {

		List<RecoveredClass> childClasses = recoveredClass.getChildClasses();
		if (childClasses.isEmpty()) {
			return null;
		}

		// The child functions should all have the same function signature so just get any one of them
		// if for some reason they don't, still have to pick one and let user decide how to update
		RecoveredClass childClass = childClasses.get(0);

		List<Address> childVftableAddresses = childClass.getVftableAddresses();
		if (childVftableAddresses.isEmpty()) {
			return null;
		}

		// get the correct child vftable for the given parent class
		for (Address childVftableAddress : childVftableAddresses) {
			RecoveredClass parentForVftable = childClass.getVftableBaseClass(childVftableAddress);
			if (parentForVftable == null) {
				continue;
			}
			if (parentForVftable.equals(recoveredClass)) {
				List<Function> childVirtualFunctionsForGivenParent =
					childClass.getVirtualFunctions(childVftableAddress);
				if (childVirtualFunctionsForGivenParent.size() < virtualFunctionNumber) {
					return null;
				}
				return childVirtualFunctionsForGivenParent.get(virtualFunctionNumber - 1);
			}
		}
		return null;
	}

	/**
	 * Method to give default names to the vfunctions in the given vftable if they don't have a name already. If they are a clone or deleting destructor name them accordingly.
	 * @param recoveredClass the given class
	 * @param vftableAddress the address of the vftable
	 * @param vftableStructureName the name of the vftable structure to be used as a prefix for the vfunctions in the given vftable
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	private void nameVfunctions(RecoveredClass recoveredClass, Address vftableAddress,
			String vftableStructureName) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		Namespace classNamespace = recoveredClass.getClassNamespace();

		List<Function> deletingDestructors = recoveredClass.getDeletingDestructors();
		List<Function> cloneFunctions = recoveredClass.getCloneFunctions();

		Iterator<Function> vfIterator =
			recoveredClass.getVirtualFunctions(vftableAddress).iterator();

		String vfunctionName;
		int tableEntry = 1;

		// get the "_for_<className> suffix for classes with multiple vftables or empty
		// string for those with single vftable
		String vfunctionSuffix = getForClassSuffix(vftableStructureName);

		while (vfIterator.hasNext()) {
			monitor.checkCanceled();
			Function vfunction = vfIterator.next();

			// create a one-up number for the next virtual function
			int entryNumber = tableEntry++;

			boolean setPrimary = false;
			boolean removeBadFID = false;
			boolean isDeletingDestructor = false;

			if (deletingDestructors.contains(vfunction)) {
				vfunctionName = DELETING_DESTRUCTOR_LABEL + vfunctionSuffix;
				setPrimary = true;
				removeBadFID = true;
				isDeletingDestructor = true;
			}

			else if (cloneFunctions.contains(vfunction)) {
				vfunctionName = CLONE_LABEL + vfunctionSuffix;
				setPrimary = true;
				removeBadFID = true;
			}

			else {
				vfunctionName = DEFAULT_VFUNCTION_PREFIX + entryNumber + vfunctionSuffix;
			}

			// can't put external functions into a namespace from this program
			if (!vfunction.isExternal()) {

				// if not already, make it a this call
				makeFunctionThiscall(vfunction);

				// put symbol on the virtual function
				Symbol vfunctionSymbol = vfunction.getSymbol();
				Namespace vfunctionNamespace = vfunctionSymbol.getParentNamespace();

				// if the name already contains deleting_destructor for this namespace don't add
				// another dd symbol
				if (hasDeletingDestructorInNamespace(vfunction.getEntryPoint(), classNamespace)) {
					continue;
				}

				if (!isDeletingDestructor &&
					isParentNamespace(vfunctionNamespace, classNamespace)) {
					String plateComment = api.getPlateComment(vfunction.getEntryPoint());
					String newComment = vfunctionNamespace.getName(true) +
						" member function inherited by " + classNamespace.getName(true);
					if (plateComment != null) {
						newComment = plateComment + "\n" + newComment;
					}
					api.setPlateComment(vfunction.getEntryPoint(), newComment);
					continue;
				}

				SourceType originalSourceType = vfunctionSymbol.getSource();
				if (originalSourceType == SourceType.DEFAULT || setPrimary) {
					createNewSymbolAtFunction(vfunction, vfunctionName, classNamespace, setPrimary,
						removeBadFID);
				}
			}
		}
	}

	private String getForClassSuffix(String vftableStructureName) {

		String vfunctionSuffix = "";
		if (vftableStructureName.contains("_for_")) {
			int index = vftableStructureName.indexOf("for_");
			if (index > 0) {
				vfunctionSuffix = "_" + vftableStructureName.substring(index);
			}
		}
		return vfunctionSuffix;
	}

	private boolean hasDeletingDestructorInNamespace(Address address, Namespace namespace)
			throws CancelledException {

		Symbol[] symbols = symbolTable.getSymbols(address);
		for (Symbol symbol : symbols) {
			monitor.checkCanceled();

			if (symbol.getName().contains("deleting_destructor") &&
				symbol.getParentNamespace().equals(namespace)) {
				return true;
			}
		}
		return false;
	}

	private boolean isParentNamespace(Namespace namespace, Namespace childNamespace) {

		RecoveredClass possibleParentClass = getClass(namespace);
		if (possibleParentClass == null) {
			return false;
		}

		RecoveredClass childClass = getClass(childNamespace);
		if (childClass == null) {
			return false;
		}

		if (childClass.equals(possibleParentClass)) {
			return false;
		}

		List<RecoveredClass> classHierarchy = childClass.getClassHierarchy();
		if (classHierarchy.contains(possibleParentClass)) {
			return true;
		}
		return false;
	}

	// may skip a parent so continue through all parents
	// for multi-inheritance, get the correct parent class for the given vftable
	/**
	 * Method to retrieve the class path of the highest ancestor with matching vfunction in its vftable
	 * @param vfunction the given virtual function
	 * @param functionDefName the given function definition name
	 * @param recoveredClass the given class
	 * @param vftableAddress the given virtual function table from the given class
	 * @return the class path for the highest ancestor with matching virtual function in its vftable
	 * @throws CancelledException when cancelled
	 * @throws InvalidNameException if functionDefName is invalid name
	 */
	CategoryPath getCategoryPathForFunctionSignature(Function vfunction, String functionDefName,
			RecoveredClass recoveredClass, Address vftableAddress)
			throws CancelledException, InvalidNameException {

		// if class has no parent, return its own classPath
		CategoryPath classPath = recoveredClass.getClassPath();
		if (!recoveredClass.hasParentClass()) {
			return classPath;
		}

		// if no ancestor has virtual functions then return the given class's class path
		List<RecoveredClass> ancestorsWithVirtualFunctions =
			getAncestorsWithVirtualFunctions(recoveredClass);
		if (ancestorsWithVirtualFunctions.size() == 0) {
			return classPath;
		}

		// if the class has multiple inheritance or single virtual inheritance get the parent from
		// the "_for_<parentName>" in the given vftable
		if (recoveredClass.hasMultipleInheritance() ||
			(recoveredClass.hasSingleInheritance() && recoveredClass.inheritsVirtualAncestor())) {
			RecoveredClass parentClass = recoveredClass.getVftableBaseClass(vftableAddress);

			// if can't recover the parent from the vftable just return the class's class path
			if (parentClass == null) {
				return classPath;
			}

			// else return the parent associated with the vftableAddress
			return parentClass.getClassPath();
		}

		// else class has normal single inheritance so just search for the matching function definition
		// in one of the class's ancestors and return the ancestor class it is found in or if not found,
		// return the current class
		List<RecoveredClass> classHierarchy = recoveredClass.getClassHierarchy();

		// classHierarchy list always contains current class in first item so skip that one
		// to search only the ancestors
		Iterator<RecoveredClass> classHierarchyIterator = classHierarchy.listIterator(1);

		// TODO: eventually use the function definition to do an equivalence check amongst
		// possible same-name vfunctions to make sure getting the correct one
		// in this case need to update the below check in dtMan to look through the .conflicts
//		FunctionDefinition functionDataType = new FunctionDefinitionDataType(vfunction, true);
//
//		functionDataType.setReturnType(vfunction.getReturnType());
//		try {
//			functionDataType.setName(functionDefName);
//		}
//		catch (DuplicateNameException e) {
//			// ignore -- don't rename if it is already the same name
//		}

		while (classHierarchyIterator.hasNext()) {
			monitor.checkCanceled();

			RecoveredClass currentClass = classHierarchyIterator.next();

			CategoryPath currentClassPath = currentClass.getClassPath();
			DataType existingDataType =
				dataTypeManager.getDataType(currentClassPath, functionDefName);

			if (existingDataType != null) {
				return currentClassPath;
			}

		}
		return classPath;

	}

	/**
	 *
	 * @param functionDefDataType the function definition
	 * @param classPath the given data type manager classPath
	 * @return pointer to function signature data type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	private PointerDataType createFunctionSignaturePointerDataType(
			FunctionDefinition functionDefDataType, CategoryPath classPath)
			throws DuplicateNameException {

		DataType existingDataType =
			dataTypeManager.getDataType(classPath, functionDefDataType.getName());

		PointerDataType functionPointerDataType;

		// If the given function definition doesn't exist in this folder make a new one and
		// make a pointer to it
		if (existingDataType == null) {
			functionDefDataType.setCategoryPath(classPath);
			functionPointerDataType = new PointerDataType(functionDefDataType, dataTypeManager);
		}
		// otherwise return a pointer to the existing one
		else {
			functionPointerDataType = new PointerDataType(existingDataType);
		}
		return functionPointerDataType;

	}

	/**
	 * Method to add precomment inside functions containing inlined constructors at approximate
	 * address of start of inlined function
	 * @param recoveredClass current class
	 * @throws Exception when cancelled
	 */
	public void createInlinedConstructorComments(RecoveredClass recoveredClass) throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();
		String className = recoveredClass.getName();

		List<Function> inlinedConstructorList = recoveredClass.getInlinedConstructorList();
		Iterator<Function> inlinedConstructorsIterator = inlinedConstructorList.iterator();

		while (inlinedConstructorsIterator.hasNext()) {
			monitor.checkCanceled();
			Function inlinedFunction = inlinedConstructorsIterator.next();

			List<Address> listOfClassRefsInFunction =
				getSortedListOfAncestorRefsInFunction(inlinedFunction, recoveredClass);

			if (!listOfClassRefsInFunction.isEmpty()) {

				Address markupAddress = listOfClassRefsInFunction.get(0);
				String markupString = classNamespace.getName(true) + "::" + className;

				String existingComment = api.getPreComment(markupAddress);
				if (existingComment != null) {
					existingComment = existingComment + "\n";
				}
				else {
					existingComment = "";
				}
				api.setPreComment(markupAddress,
					existingComment + "inlined constructor: " + markupString);
				bookmarkAddress(markupAddress, INLINE_CONSTRUCTOR_BOOKMARK + " " + markupString);
			}
		}
	}

	/**
	 * Method to add precomment inside functions containing inlined destructors at approximate
	 * address of start of inlined function
	 * @param recoveredClass current class
	 * @throws Exception when cancelled
	 */
	public void createInlinedDestructorComments(RecoveredClass recoveredClass) throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();
		String className = recoveredClass.getName();

		List<Function> inlinedDestructorList = recoveredClass.getInlinedDestructorList();
		Iterator<Function> inlinedDestructorIterator = inlinedDestructorList.iterator();
		while (inlinedDestructorIterator.hasNext()) {
			monitor.checkCanceled();
			Function destructorFunction = inlinedDestructorIterator.next();
			Address classVftableRef =
				getFirstClassVftableReference(recoveredClass, destructorFunction);

			if (classVftableRef == null) {
				continue;
			}

			String markupString = classNamespace.getName(true) + "::~" + className;
			api.setPreComment(classVftableRef, "inlined destructor: " + markupString);

			bookmarkAddress(classVftableRef, INLINE_DESTRUCTOR_BOOKMARK + " " + markupString);
		}
	}

	/**
	 * Method to add label on functions with inlined constructor or destructors but couldn't tell which
	 * @param recoveredClass current class
	 * @throws Exception when cancelled
	 */
	public void createIndeterminateInlineComments(RecoveredClass recoveredClass) throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();

		List<Function> functionsContainingInlineList = recoveredClass.getIndeterminateInlineList();
		Iterator<Function> functionsContainingInlineIterator =
			functionsContainingInlineList.iterator();
		while (functionsContainingInlineIterator.hasNext()) {
			monitor.checkCanceled();
			Function functionContainingInline = functionsContainingInlineIterator.next();

			Address classVftableRef =
				getFirstClassVftableReference(recoveredClass, functionContainingInline);

			if (classVftableRef == null) {
				continue;
			}

			String markupString = "inlined constructor or destructor (approx location) for " +
				classNamespace.getName(true);
			api.setPreComment(classVftableRef, markupString);

			bookmarkAddress(classVftableRef, INDETERMINATE_INLINE_BOOKMARK + " " + markupString);
		}
	}

	/**
	 * Method to add label on constructor or destructors but couldn't tell which
	 * @param recoveredClass current class
	 * @param classStruct the class structure for the given class
	 * @throws Exception when cancelled
	 */
	public void createIndeterminateLabels(RecoveredClass recoveredClass, Structure classStruct)
			throws Exception {

		Namespace classNamespace = recoveredClass.getClassNamespace();
		String className = recoveredClass.getName();

		List<Function> unknownIfConstructorOrDestructorLIst = recoveredClass.getIndeterminateList();
		Iterator<Function> unknownsIterator = unknownIfConstructorOrDestructorLIst.iterator();
		while (unknownsIterator.hasNext()) {
			monitor.checkCanceled();
			Function indeterminateFunction = unknownsIterator.next();

			if (nameVfunctions) {
				createNewSymbolAtFunction(indeterminateFunction,
					className + "_Constructor_or_Destructor", classNamespace, false, false);
			}

		}
	}

	/**
	 * Method to create an ANALYSIS bookmark at the given address with the given comment
	 * @param address the given address
	 * @param comment the given comment
	 */
	public void bookmarkAddress(Address address, String comment) {

		BookmarkManager bookmarkMgr = program.getBookmarkManager();

		Bookmark bookmark =
			bookmarkMgr.getBookmark(address, BookmarkType.ANALYSIS, BOOKMARK_CATEGORY);
		String bookmarkComment;
		if (bookmark != null && !bookmark.getComment().equals(comment) &&
			!containsString(bookmark.getComment(), comment)) {
			bookmarkComment = bookmark.getComment() + " + " + comment;
		}
		else {
			bookmarkComment = comment;
		}
		bookmarkMgr.setBookmark(address, BookmarkType.ANALYSIS, BOOKMARK_CATEGORY, bookmarkComment);
	}

	public void bookmarkAddress(Address address, String comment, String category) {

		BookmarkManager bookmarkMgr = program.getBookmarkManager();

		Bookmark bookmark = bookmarkMgr.getBookmark(address, BookmarkType.ANALYSIS, category);
		String bookmarkComment;
		if (bookmark != null && !bookmark.getComment().equals(comment) &&
			!containsString(bookmark.getComment(), comment)) {
			bookmarkComment = bookmark.getComment() + " + " + comment;
		}
		else {
			bookmarkComment = comment;
		}
		bookmarkMgr.setBookmark(address, BookmarkType.ANALYSIS, category, bookmarkComment);
	}

	/**
	 * Method to determine if the given comment string that has pieces separated by +'s has any
	 * piece exactly equal to the given string.
	 * @param bookmarkComment the bookmark comment string
	 * @param string the string to search for within the comment
	 * @return true if string is contained exactly within the +'s
	 */
	private boolean containsString(String bookmarkComment, String string) {

		// first split comment into pieces between the +'s
		String[] commentPieces = bookmarkComment.split("\\+");
		for (String piece : commentPieces) {
			// remove leading and trailing spaces from each piece
			int len = piece.length();

			if (piece.charAt(len - 1) == ' ') {
				piece = piece.substring(0, len - 2);
			}

			if (piece.charAt(0) == ' ') {
				piece = piece.substring(1);
			}

			// return true if any piece exactly equals the new string
			if (piece.equals(string)) {
				return true;
			}
		}

		// return false if string does not match any of the pieces
		return false;

	}

	/**
	 * Method to find all class functions that are on both the vfunction list and the possible 
	 * constructor/destructor list for the same class 
	 * @param recoveredClasses the list of all known classes
	 * @return all functions on both their class constructor/destructor list and virtual function 
	 * list
	 * @throws CancelledException if cancelled
	 */
	private Set<Function> getTwoCallCommonFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		Set<Function> twoCallCommonFunctions = new HashSet<Function>();

		for (RecoveredClass recoveredClass : recoveredClasses) {
			monitor.checkCanceled();

			List<Function> virtualFunctions = recoveredClass.getAllVirtualFunctions();
			List<Function> cdFunctions = recoveredClass.getConstructorOrDestructorFunctions();
			for (Function cdFunction : cdFunctions) {
				monitor.checkCanceled();

				// if it isn't on both lists continue
				if (!virtualFunctions.contains(cdFunction)) {
					continue;
				}

				// if it doesn't have exactly two calls continue
				if (!hasNCalls(cdFunction, 2)) {
					continue;
				}

				// else add it to the set to return
				twoCallCommonFunctions.add(cdFunction);

			}
		}
		return twoCallCommonFunctions;
	}

	/**
	 * Method to find operator delete function(s) using common called functions from class virtual
	 * functions
	 * @param recoveredClasses the list of classes
	 * @return a Set of operator delete functions if found or an empty Set if not
	 * @throws CancelledException if cancelled
	 */
	private Set<Function> findOperatorDeletesUsingCalledCommonFunction(
			List<RecoveredClass> recoveredClasses) throws CancelledException {

		Set<Function> operatorDeletes = new HashSet<Function>();
		HashMap<Function, Integer> operatorDeleteCountMap = new HashMap<Function, Integer>();

		// get all the functions that are on both the virtual function list and cd list for
		// their respective class
		Set<Function> twoCallCommonFunctions = getTwoCallCommonFunctions(recoveredClasses);
		if (twoCallCommonFunctions.isEmpty()) {
			return operatorDeletes;
		}

		// go through all the functions and update the maps if the function calls a constructor
		// or destructor in either position

		int numPossibleOpDeleteFunctions = 0;
		int highestOpDeleteCount = 0;
		Function mostCommonOpDelete = null;

		for (Function function : twoCallCommonFunctions) {
			monitor.checkCanceled();

			// get first called function - get the thunked one if it is a thunk
			Function firstCalledFunction =
				extendedFlatAPI.getCalledFunctionByCallOrder(function, 1, true);

			// skip if computed call (ie call eax)
			if (firstCalledFunction == null) {
				continue;
			}

			// get second called function - not the thunked one
			// need the actual called function for the operator test
			Function secondCalledFunction =
				extendedFlatAPI.getCalledFunctionByCallOrder(function, 2, false);

			if (secondCalledFunction == null) {
				continue;
			}

			// if the first called function is on the global list of c/ds then update the
			// operator delete count map with the second called function
			if (allPossibleConstructorsAndDestructors.contains(firstCalledFunction)) {

				numPossibleOpDeleteFunctions++;

				int count = operatorDeleteCountMap.compute(firstCalledFunction,
					(k, v) -> v == null ? 1 : v + 1);

				if (highestOpDeleteCount < count) {
					highestOpDeleteCount = count;
					mostCommonOpDelete = secondCalledFunction;
				}
				continue;
			}

		}

		// TODO: set some percentage threshold given the number of good possibles
		if (highestOpDeleteCount > numPossibleOpDeleteFunctions / 2) {

			Set<Function> thunkFamily = new HashSet<Function>();
			thunkFamily.add(mostCommonOpDelete);
			thunkFamily.addAll(getAllThunkFunctions(mostCommonOpDelete));

			operatorDeletes.addAll(thunkFamily);
		}

		return operatorDeletes;
	}

	private Set<Function> findOperatorNewsUsingCalledCommonFunction(
			List<RecoveredClass> recoveredClasses) throws CancelledException {

		Set<Function> operatorNews = new HashSet<Function>();

		HashMap<Function, Integer> operatorNewCountMap = new HashMap<Function, Integer>();

		// get all the functions that are on both the virtual function list and cd list for
		// their respective class
		Set<Function> twoCallCommonFunctions = getTwoCallCommonFunctions(recoveredClasses);
		if (twoCallCommonFunctions.isEmpty()) {
			return operatorNews;
		}

		// get count map of all first called functions 
		int numPossibleOpNewFunctions = 0;
		int highestOpNewCount = 0;
		Function mostCommonOpNew = null;

		for (Function function : twoCallCommonFunctions) {
			monitor.checkCanceled();

			// get first called function - not the thunked
			// need the actual called function for the operator new test
			Function firstCalledFunction =
				extendedFlatAPI.getCalledFunctionByCallOrder(function, 1, false);

			if (firstCalledFunction == null) {
				continue;
			}

			// get second called function - thunked function if a thunk
			Function secondCalledFunction =
				extendedFlatAPI.getCalledFunctionByCallOrder(function, 2, true);

			if (secondCalledFunction == null) {
				continue;
			}

			// if the second called function is on the global list of c/ds then update the
			// operator new count map with the first called function
			if (allPossibleConstructorsAndDestructors.contains(secondCalledFunction)) {

				numPossibleOpNewFunctions++;
				int count = operatorNewCountMap.compute(firstCalledFunction,
					(k, v) -> v == null ? 1 : v + 1);

				if (highestOpNewCount < count) {
					highestOpNewCount = count;
					mostCommonOpNew = firstCalledFunction;
				}

			}
		}

		// TODO: set some percentage threshold given the number of good possibles
		if (highestOpNewCount > numPossibleOpNewFunctions / 2) {

			Set<Function> thunkFamily = new HashSet<Function>();
			thunkFamily.add(mostCommonOpNew);
			thunkFamily.addAll(getAllThunkFunctions(mostCommonOpNew));
			operatorNews.addAll(thunkFamily);
		}

		return operatorNews;
	}

	private Set<Function> getAllThunkFunctions(Function function) throws CancelledException {

		FunctionManager functionManager = program.getFunctionManager();

		Set<Function> thunkFunctions = new HashSet<Function>();

		Address[] functionThunkAddresses = function.getFunctionThunkAddresses(true);
		if (functionThunkAddresses == null) {
			return thunkFunctions;
		}

		for (Address address : functionThunkAddresses) {
			monitor.checkCanceled();

			Function thunkFunction = functionManager.getFunctionAt(address);
			thunkFunctions.add(thunkFunction);
		}

		return thunkFunctions;
	}

	/**
	 * Method to remove functions from the class constructor/destructor lists (and the overall list)
	 * that are not self-contained constructor/destructor functions. Add them to the list of
	 * functions that contain inlined constructors or destructors.
	 * NOTE: this must be called after the global const/dest list is created but before
	 * functions get added to the other class lists
	 * @param recoveredClasses list of classes to process
	 * @throws CancelledException if cancelled
	 */
	public void separateInlinedConstructorDestructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();
			List<Function> indeterminateFunctions = recoveredClass.getIndeterminateList();
			Iterator<Function> indeterminateIterator = indeterminateFunctions.iterator();
			while (indeterminateIterator.hasNext()) {
				monitor.checkCanceled();
				Function indeterminateFunction = indeterminateIterator.next();

				List<Address> vftableReferenceList = getVftableReferences(indeterminateFunction);
				if (vftableReferenceList == null) {
					continue;
				}

				// if inline, put on separate list and remove from indeterminate list
				// process later
				if (vftableReferenceList.size() > 1) {
					if (!areVftablesInSameClass(vftableReferenceList)) {
						recoveredClass.addIndeterminateInline(indeterminateFunction);
						indeterminateIterator.remove();
					}

					continue;
				}
			}
		}
	}

	/**
	 * Method to add the structure components from the given structureToAdd from the given starting
	 * offset to the given ending offset of the  to the given structure at the given offset
	 * @param structure the structure to add to
	 * @param structureToAdd the structure to add a subset of components from to the given structure
	 * @param startOffset the starting offset of where to start adding in the container structure
	 * @param dataLength the total length of the data components to add
	 * @return true if the structure was updated or false if the components cannot be added
	 * @throws CancelledException if cancelled
	 */
	public boolean addIndividualComponentsToStructure(Structure structure, Structure structureToAdd,
			int startOffset, int dataLength) throws CancelledException {

		// this check does not allow growing of structure. It only allows adding to the structure if
		// the block of components to add will fit at the given offset
		if (!EditStructureUtils.canAdd(structure, startOffset, dataLength, false, monitor)) {
			return false;
		}

		for (DataTypeComponent dataTypeComponent : structureToAdd.getDefinedComponents()) {

			monitor.checkCanceled();

			// only copy the components up to the given total dataLength to copy
			if ((dataTypeComponent.getOffset() + dataTypeComponent.getLength()) > dataLength) {
				break;
			}

			structure.replaceAtOffset(startOffset + dataTypeComponent.getOffset(),
				dataTypeComponent.getDataType(), -1, dataTypeComponent.getFieldName(),
				dataTypeComponent.getComment());
		}
		return true;
	}

	/**
	 * Method to add alignment to the given length based on the default program address size
	 * @param len the given length
	 * @return len updated with alignment size
	 */
	public int addAlignment(int len) {

		int mod = len % defaultPointerSize;
		int alignment = 0;
		if (mod != 0) {
			alignment = defaultPointerSize - mod;
			len += alignment;
		}
		return len;
	}

	/**
	 * Method to retrieve the offset of the virtual parent of the given class in the given structure
	 * @param recoveredClass the given class
	 * @param structure the given structure
	 * @return the offset of the virtual parent of the given class in the given structure
	 * @throws CancelledException if cancelled
	 */
	public int getOffsetOfVirtualParent(RecoveredClass recoveredClass, Structure structure)
			throws CancelledException {

		DataTypeComponent[] definedComponents = structure.getDefinedComponents();

		for (DataTypeComponent dataTypeComponent : definedComponents) {
			// if run into a virtual parent class structure, return its offset
			monitor.checkCanceled();
			if (isVirtualParentClassStructure(recoveredClass, dataTypeComponent.getDataType())) {
				return dataTypeComponent.getOffset();
			}
		}
		return NONE;

	}

	/**
	 * Method to retrieve the offset of the virtual parent of the given class in the given structure
	 * @param recoveredClass the given class
	 * @param structure the given structure
	 * @return the offset of the virtual parent of the given class in the given structure
	 * @throws CancelledException if cancelled
	 */
	public int getEndOfInternalDataOffset(RecoveredClass recoveredClass, Structure structure)
			throws CancelledException {

		List<Structure> virtualParentClassStructures =
			getVirtualParentClassStructures(recoveredClass);

		// if there are no virtual parents there will be no internal data
		if (virtualParentClassStructures.size() == 0) {
			return NONE;
		}

		DataTypeComponent[] definedComponents = structure.getDefinedComponents();

		if (definedComponents.length == 0) {
			return NONE;
		}

		List<Integer> definedOffsets = new ArrayList<Integer>();

		for (DataTypeComponent dataTypeComponent : definedComponents) {

			monitor.checkCanceled();
			definedOffsets.add(dataTypeComponent.getOffset());
		}
		Collections.sort(definedOffsets);

		boolean firstDefined = true;
		int nextOffset = 0;

		// structures that contain virtual parents have data in the middle of the structure
		// between non-virtual and virtual parents
		// loop to find the first defined offset after the segment of undefineds
		for (Integer currentOffset : definedOffsets) {

			monitor.checkCanceled();

			DataTypeComponent dataTypeComponent = structure.getComponentAt(currentOffset);

			if (firstDefined) {
				firstDefined = false;
				nextOffset = currentOffset + dataTypeComponent.getLength();
				continue;
			}

			// if the current offset is differnet than the next offset then it is after the gap
			// of undefineds and we have found the offset we need to return
			if (currentOffset != nextOffset) {
				return currentOffset;
			}

			// if the currentOffset equals what we calculated as the next offset then the
			// current data is contiguous to the last data so no undefineds between them and
			// can continue looking for the gap of undefines

			nextOffset = currentOffset + dataTypeComponent.getLength();

		}
		return NONE;

	}

	/**
	 * Method to determine if the given data type is the virtual parent class structure for the given class
	 * @param recoveredClass the given class
	 * @param dataType the given data type
	 * @return true if the given data type is the virtual parent class structure for the given class
	 * @throws CancelledException if cancelled
	 */
	private boolean isVirtualParentClassStructure(RecoveredClass recoveredClass, DataType dataType)
			throws CancelledException {

		// return false right away if it isn't even a structure
		if (!(dataType instanceof Structure)) {
			return false;
		}

		String parentClassName = dataType.getName();

		Map<RecoveredClass, Boolean> parentToBaseTypeMap = recoveredClass.getParentToBaseTypeMap();

		Set<RecoveredClass> parentClasses = parentToBaseTypeMap.keySet();
		Iterator<RecoveredClass> parentClassIterator = parentClasses.iterator();
		while (parentClassIterator.hasNext()) {

			monitor.checkCanceled();
			RecoveredClass parentClass = parentClassIterator.next();
			if (parentClass.getName().equals(parentClassName)) {
				Boolean isVirtualParent = parentToBaseTypeMap.get(parentClass);
				if (isVirtualParent) {
					return true;
				}
			}

		}
		return false;

	}

	/**
	 * Method to determine if the given data type is the virtual parent class structure for the given class
	 * @param recoveredClass the given class
	 * @return true if the given data type is the virtual parent class structure for the given class
	 * @throws CancelledException if cancelled
	 */
	private List<Structure> getVirtualParentClassStructures(RecoveredClass recoveredClass)
			throws CancelledException {

		Map<RecoveredClass, Boolean> parentToBaseTypeMap = recoveredClass.getParentToBaseTypeMap();
		List<Structure> virtualParentStructures = new ArrayList<Structure>();

		Set<RecoveredClass> parentClasses = parentToBaseTypeMap.keySet();

		// if no parents, return empty list
		if (parentClasses.isEmpty()) {
			return virtualParentStructures;
		}

		for (RecoveredClass parentClass : parentClasses) {

			monitor.checkCanceled();

			Boolean isVirtualParent = parentToBaseTypeMap.get(parentClass);
			if (isVirtualParent) {
				Structure parentStructure = getClassStructureFromDataTypeManager(parentClass);
				if (parentStructure != null) {
					virtualParentStructures.add(parentStructure);
				}
			}
		}

		return virtualParentStructures;

	}

	/**
	 * Method to determine if the given data type is the virtual parent class structure for the given class
	 * @param recoveredClass the given class
	 * @return true if the given data type is the virtual parent class structure for the given class
	 * @throws CancelledException if cancelled
	 */
	protected List<RecoveredClass> getVirtualParentClasses(RecoveredClass recoveredClass)
			throws CancelledException {

		Map<RecoveredClass, Boolean> parentToBaseTypeMap = recoveredClass.getParentToBaseTypeMap();
		List<RecoveredClass> virtualParents = new ArrayList<RecoveredClass>();

		Set<RecoveredClass> parentClasses = parentToBaseTypeMap.keySet();
		Iterator<RecoveredClass> parentClassIterator = parentClasses.iterator();
		while (parentClassIterator.hasNext()) {

			monitor.checkCanceled();
			RecoveredClass parentClass = parentClassIterator.next();

			Boolean isVirtualParent = parentToBaseTypeMap.get(parentClass);
			if (isVirtualParent) {
				virtualParents.add(parentClass);
			}
		}

		return virtualParents;

	}

	/**
	 * Method to determine if all of a class's vftables are accounted for in its classOffsetToVftableMap
	 * @param recoveredClass the given class
	 * @return true if all vftables have a mapping, false otherwise
	 */
	public boolean isClassOffsetToVftableMapComplete(RecoveredClass recoveredClass) {

		if (recoveredClass.getClassOffsetToVftableMap()
				.values()
				.containsAll(recoveredClass.getVftableAddresses())) {
			return true;
		}
		return false;
	}

	/**
	 * Method to find destructors that have no parameters or return type
	 * @param recoveredClasses list of classes to process
	 * @throws CancelledException if cancelled
	 */
	public void findDestructorsWithNoParamsOrReturn(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();
		while (recoveredClassIterator.hasNext()) {

			monitor.checkCanceled();

			RecoveredClass recoveredClass = recoveredClassIterator.next();
			List<Function> indeterminateFunctions = recoveredClass.getIndeterminateList();
			Iterator<Function> indeterminateIterator = indeterminateFunctions.iterator();
			while (indeterminateIterator.hasNext()) {
				monitor.checkCanceled();
				Function indeterminateFunction = indeterminateIterator.next();

				DataType returnDataType =
					decompilerUtils.getDecompilerReturnType(indeterminateFunction);
				if (returnDataType == null) {
					continue;
				}

				String returnDataName = returnDataType.getDisplayName();

				ParameterDefinition[] params =
					decompilerUtils.getParametersFromDecompiler(indeterminateFunction);
				int numberParams = 0;

				if (params == null) {
					numberParams = indeterminateFunction.getParameterCount();
				}
				else {
					numberParams = params.length;
				}

				if (numberParams == 0 && returnDataName.equals("void")) {

					Address firstVftableReference =
						getFirstVftableReferenceInFunction(indeterminateFunction);
					if (firstVftableReference == null) {
						continue;
					}

					FillOutStructureCmd fillCmd =
						runFillOutStructureCmd(indeterminateFunction, firstVftableReference);

					if (fillCmd == null) {
						continue;
					}

					List<OffsetPcodeOpPair> stores = fillCmd.getStorePcodeOps();
					List<OffsetPcodeOpPair> loads = fillCmd.getLoadPcodeOps();
					stores = removePcodeOpsNotInFunction(indeterminateFunction, stores);
					loads = removePcodeOpsNotInFunction(indeterminateFunction, loads);

					if (loads == null || stores == null) {
						continue;
					}

					if (stores.size() == 1 && loads.size() == 0) {
						recoveredClass.addNonThisDestructor(indeterminateFunction);
						indeterminateIterator.remove();
					}
				}
			}
		}
	}

	/**
	 * Method to determine if the vftable reference(s) in a constructor are not in the first code
	 * block for constructors that have more than one block
	 * @param recoveredClasses List of RecoveredClass objects
	 * @throws CancelledException when cancelled
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void findMoreInlinedConstructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException, InvalidInputException, DuplicateNameException {
		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();
			List<Function> constructorList = recoveredClass.getConstructorList();
			Iterator<Function> constructorIterator = constructorList.iterator();
			while (constructorIterator.hasNext()) {
				monitor.checkCanceled();
				Function constructor = constructorIterator.next();

				// get the references to the vftable(s) that are referenced in this function

				List<Address> referencesToVftablesFromFunction = getVftableReferences(constructor);

				if (referencesToVftablesFromFunction == null) {
					continue;
				}

				Collections.sort(referencesToVftablesFromFunction);
				Address firstVftableReferenceAddress = referencesToVftablesFromFunction.get(0);

				Address firstEndOfBlock = getEndOfFirstBlockAddress(constructor);
				if (firstEndOfBlock != null) {

					// not as reliable for virtual or multi-virtual inheritance so skip
					if (recoveredClass.inheritsVirtualAncestor() ||
						recoveredClass.hasMultipleVirtualInheritance()) {
						continue;
					}

					// if the first vftable reference is not in the first code block and the 
					// constructor calls any non-constructors before the vtable reference, 
					// the constructor function is really another function with the constructor 
					// function inlined in it
					if (firstVftableReferenceAddress.compareTo(firstEndOfBlock) > 0) {
						if (doesFunctionCallAnyNonConstructorsBeforeVtableReference(
							recoveredClass, constructor, firstVftableReferenceAddress)) {

							// remove from the allConstructors too
							addInlinedConstructorToClass(recoveredClass, constructor);
							nonClassInlines.add(constructor);
							constructorIterator.remove();
							removeFromAllConstructors(constructor);

						}
					}
				}
			}
		}

	}

	/**
	 * Method to retrieve the address of the end of the first code block in the given function
	 * @param function the given function
	 * @return the address of the end of the first code block in the given function
	 * @throws CancelledException if cancelled
	 */
	private Address getEndOfFirstBlockAddress(Function function) throws CancelledException {

		Address instructionAddress = null;
		Listing listing = program.getListing();
		AddressSetView functionAddressSet = function.getBody();
		InstructionIterator instructionsIterator =
			listing.getInstructions(functionAddressSet, true);
		while (instructionsIterator.hasNext()) {
			monitor.checkCanceled();
			Instruction instruction = instructionsIterator.next();
			if (!instruction.isFallthrough() && (!instruction.getFlowType().isCall())) {
				instructionAddress = instruction.getAddress();
				return instructionAddress;
			}

		}

		return instructionAddress;

	}

	/**
	 * Method to run the FillOutStructureCmd and return a FillOutStructureCmd object when
	 * a high variable used to run the cmd is found that stores the given firstVftableReference
	 * address.
	 * @param function the given function
	 * @param firstVftableReference the first vftableReference in the given function
	 * @return FillOutStructureCmd for the highVariable that stores the firstVftableReference address
	 * or null if one isn't found.
	 * @throws CancelledException if cancelled
	 */
	public FillOutStructureCmd runFillOutStructureCmd(Function function,
			Address firstVftableReference) throws CancelledException {

		Address vftableAddress = getVftableAddress(firstVftableReference);

		if (vftableAddress == null) {
			return null;
		}

		// get the decompiler highFunction
		HighFunction highFunction = decompilerUtils.getHighFunction(function);

		if (highFunction == null) {
			return null;
		}

		List<HighVariable> highVariables = new ArrayList<HighVariable>();

		// if there are params add the first or the "this" param to the list to be checked first
		// It is the most likely to store the vftablePtr
		if (highFunction.getFunctionPrototype().getNumParams() > 0) {

			HighVariable thisParam =
				highFunction.getFunctionPrototype().getParam(0).getHighVariable();
			if (thisParam != null) {
				highVariables.add(thisParam);
			}
		}

		// add the other high variables that store vftable pointer
		highVariables
				.addAll(getVariableThatStoresVftablePointer(highFunction, firstVftableReference));

		Iterator<HighVariable> highVariableIterator = highVariables.iterator();

		while (highVariableIterator.hasNext()) {

			HighVariable highVariable = highVariableIterator.next();
			monitor.checkCanceled();

			FillOutStructureCmd fillCmd = new FillOutStructureCmd(program, location, tool);
			fillCmd.processStructure(highVariable, function);
			List<OffsetPcodeOpPair> stores = fillCmd.getStorePcodeOps();
			stores = removePcodeOpsNotInFunction(function, stores);

			// this method checks the storedPcodeOps to see if one is the vftable address
			Address storedVftableAddress = getStoredVftableAddress(stores);
			if (storedVftableAddress == null) {
				continue;
			}

			if (storedVftableAddress.equals(vftableAddress)) {
				return fillCmd;
			}

		}
		return null;
	}

	/**
	 * Method to figure out the indetermined inlined functions from each class as either combination
	 * constructor/inlined constructor or destrucor/inlined destructor. The method first uses any
	 * known called constructors or destructors to help determine which type then calls a method to
	 * determine, using vftable order, which class contains the constructor/destructor and which
	 * contains the inlined constructor/destructor.
	 * @param recoveredClasses List of classes
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if error setting return type
	 * @throws CancelledException when cancelled and others
	 */
	public void processInlinedConstructorsAndDestructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException, InvalidInputException, DuplicateNameException,
			CircularDependencyException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();

			List<Function> inlineFunctionsList =
				new ArrayList<>(recoveredClass.getIndeterminateInlineList());

			Iterator<Function> inlineIterator = inlineFunctionsList.iterator();
			while (inlineIterator.hasNext()) {
				monitor.checkCanceled();

				Function inlineFunction = inlineIterator.next();

				// get the addresses in the function that refer to classes either by
				// referencing a vftable in a class or by calling a function in a class
				// TODO: add the atexit refs and then check them - make a map of atexit call to class map if not already
				Map<Address, RecoveredClass> referenceToClassMap =
					getReferenceToClassMap(recoveredClass, inlineFunction);
				List<Address> referencesToFunctions =
					extendedFlatAPI.getReferencesToFunctions(referenceToClassMap);

				// if some of the references are to functions figure out if they are
				// constructors destructors or add them to list of indetermined
				boolean isConstructor = false;
				boolean isDestructor = false;
				List<Address> referenceToIndeterminates = new ArrayList<Address>();

				if (!referencesToFunctions.isEmpty()) {
					Iterator<Address> functionReferenceIterator = referencesToFunctions.iterator();
					while (functionReferenceIterator.hasNext()) {

						monitor.checkCanceled();
						Address functionReference = functionReferenceIterator.next();
						Function function =
							extendedFlatAPI.getReferencedFunction(functionReference, true);

						if (function == null) {
							continue;
						}

						if (getAllConstructors().contains(function) ||
							getAllInlinedConstructors().contains(function)) {
							isConstructor = true;
							continue;
						}

						if (getAllDestructors().contains(function) ||
							getAllInlinedDestructors().contains(function)) {
							isDestructor = true;
							continue;
						}

						// TODO: refactor to make this function and refactor method that uses
						// it to use function instead of refiguring it out
						referenceToIndeterminates.add(functionReference);

					}

				}

				// if one or more is a constructor and none are destructors then the indeterminate
				// inline is is an inlined constructor
				if (isConstructor == true && isDestructor == false) {
					processInlineConstructor(recoveredClass, inlineFunction, referenceToClassMap);
				}
				// if one or more is a destructor and none are constructors then the indeterminate
				// inline is an inlined destructor
				else if (isConstructor == false && isDestructor == true) {
					processInlineDestructor(recoveredClass, inlineFunction, referenceToClassMap);
				}
				else {

					// otherwise, use pcode info to figure out if inlined constructor or destructor
					//If not already, make function a this call
					makeFunctionThiscall(inlineFunction);

					List<OffsetPcodeOpPair> loads = getLoadPcodeOpPairs(inlineFunction);
					List<OffsetPcodeOpPair> stores = getStorePcodeOpPairs(inlineFunction);

					if (loads == null || stores == null) {
						Address firstVftableReferenceInFunction =
							getFirstVftableReferenceInFunction(inlineFunction);
						if (firstVftableReferenceInFunction == null) {
							continue;
						}
						FillOutStructureCmd fillOutStructureCmd =
							runFillOutStructureCmd(inlineFunction, firstVftableReferenceInFunction);

						if (fillOutStructureCmd == null) {
							continue;
						}

						loads = fillOutStructureCmd.getLoadPcodeOps();
						loads = removePcodeOpsNotInFunction(inlineFunction, loads);
						stores = fillOutStructureCmd.getStorePcodeOps();
						stores = removePcodeOpsNotInFunction(inlineFunction, stores);

						updateFunctionToStorePcodeOpsMap(inlineFunction, stores);
						updateFunctionToLoadPcodeOpsMap(inlineFunction, loads);

					}

					if (loads == null || stores == null) {
						continue;
					}

					// inlined constructor
					if (stores.size() > 1 && loads.size() == 0) {
						processInlineConstructor(recoveredClass, inlineFunction,
							referenceToClassMap);
						isConstructor = true;
					}

					// inlined destructor
					else if (stores.size() == 1 && loads.size() > 0) {
						processInlineDestructor(recoveredClass, inlineFunction,
							referenceToClassMap);
						isDestructor = true;
					}
				}

				if (!referenceToIndeterminates.isEmpty()) {
					// make the other referenced indeterminate c/d functions constructors
					if (isConstructor == true && isDestructor == false) {
						createListedConstructorFunctions(referenceToClassMap,
							referenceToIndeterminates);
						continue;
					}
					// make the other referenced indeterminate c/d functions destructors
					if (isConstructor == false && isDestructor == true) {
						createListedDestructorFunctions(referenceToClassMap,
							referenceToIndeterminates);
						continue;
					}

				}

			}

		}
	}

	/**
	 * Method to retrieve the offset of the class data in the given structure
	 * @param recoveredClass the given class
	 * @param structure the given structure
	 * @return the offset of the class data in the given structure or NONE if there isn't one
	 * @throws CancelledException if cancelled
	 */
	public int getDataOffset(RecoveredClass recoveredClass, Structure structure)
			throws CancelledException {

		int endOfInternalDataOffset = getEndOfInternalDataOffset(recoveredClass, structure);

		int endOfData;
		if (endOfInternalDataOffset == NONE) {
			endOfData = structure.getLength();
		}
		else {
			// end of data is beginning of virt parent
			endOfData = endOfInternalDataOffset;
		}

		int dataLength =
			EditStructureUtils.getNumberOfUndefinedsBeforeOffset(structure, endOfData, monitor);
		if (dataLength < 0) {
			return NONE;
		}

		return endOfData - dataLength;

	}

	/**
	 * Method to process remaining indeterminate functions to determine if they are constructors or destructors
	 * @param recoveredClasses list of classes
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	public void processRemainingIndeterminateConstructorsAndDestructors(
			List<RecoveredClass> recoveredClasses) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		Iterator<RecoveredClass> classIterator = recoveredClasses.iterator();
		while (classIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = classIterator.next();

			List<Function> indeterminateList = recoveredClass.getIndeterminateList();
			Iterator<Function> indeterminateIterator = indeterminateList.iterator();
			while (indeterminateIterator.hasNext()) {
				monitor.checkCanceled();
				Function indeterminateFunction = indeterminateIterator.next();

				// first try identifying useing known constructors and destructors
				boolean callsKnownConstructor = callsKnownConstructor(indeterminateFunction);
				boolean callsKnownDestrutor = callsKnownDestructor(indeterminateFunction);
				boolean callsAtexit =
					extendedFlatAPI.doesFunctionACallFunctionB(indeterminateFunction, atexit);

				if (callsKnownConstructor && !callsKnownDestrutor) {
					addConstructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
					continue;
				}
				if (!callsKnownConstructor && callsKnownDestrutor) {
					addDestructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
					continue;
				}

				if (!callsKnownConstructor && callsAtexit) {
					addDestructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
					continue;
				}

				// Next try identifying constructors using decompiler return type
				DataType decompilerReturnType =
					decompilerUtils.getDecompilerReturnType(indeterminateFunction);
				if (decompilerReturnType != null) {

					String returnDataName = decompilerReturnType.getDisplayName();
					if (returnDataName.contains("*") && !isFidFunction(indeterminateFunction)) {

						addConstructorToClass(recoveredClass, indeterminateFunction);
						indeterminateIterator.remove();
						continue;
					}
				}

				// Next try identifying using load/store information
				List<OffsetPcodeOpPair> loads = getLoadPcodeOpPairs(indeterminateFunction);

				List<OffsetPcodeOpPair> stores = getStorePcodeOpPairs(indeterminateFunction);

				if (loads == null || stores == null) {
					Address firstVftableReferenceInFunction =
						getFirstVftableReferenceInFunction(indeterminateFunction);
					if (firstVftableReferenceInFunction == null) {
						continue;
					}
					FillOutStructureCmd fillOutStructureCmd = runFillOutStructureCmd(
						indeterminateFunction, firstVftableReferenceInFunction);

					if (fillOutStructureCmd == null) {
						continue;
					}

					loads = fillOutStructureCmd.getLoadPcodeOps();
					loads = removePcodeOpsNotInFunction(indeterminateFunction, loads);
					stores = fillOutStructureCmd.getStorePcodeOps();
					stores = removePcodeOpsNotInFunction(indeterminateFunction, stores);

					updateFunctionToStorePcodeOpsMap(indeterminateFunction, stores);
					updateFunctionToLoadPcodeOpsMap(indeterminateFunction, loads);

				}

				if (loads == null || stores == null) {
					continue;
				}

				if (stores.size() > 1 && loads.size() == 0) {
					addConstructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
				}
				else if (stores.size() == 1 && loads.size() > 0) {
					addDestructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
				}

			}
		}

	}

	/**
	 * Method to determine if the given function is a "FID" function or one that has had function
	 * signature assigned by the FID (Function ID) Analyzer
	 * @param function the given function
	 * @return true if function is a FID function, false otherwise
	 */
	private boolean isFidFunction(Function function) {

		Address address = function.getEntryPoint();

		BookmarkManager bm = program.getBookmarkManager();

		Bookmark bookmark = bm.getBookmark(address, BookmarkType.ANALYSIS, "Function ID Analyzer");

		if (bookmark == null) {
			return false;
		}
		return true;
	}

	/**
	 * Method to identify missing functions using param to _atexit function
	 * because it always is passed a pointer to a function.
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if return type is not a fixed length
	 */
	public void findFunctionsUsingAtexit() throws CancelledException, InvalidInputException {

		Function atexitFunction = null;
		List<Function> atexitFunctions = extendedFlatAPI.getGlobalFunctions("_atexit");
		if (atexitFunctions.size() != 1) {
			return;
		}

		atexitFunction = atexitFunctions.get(0);
		atexit = atexitFunction;

		ReferenceIterator referenceIterator =
			program.getReferenceManager().getReferencesTo(atexitFunction.getEntryPoint());
		while (referenceIterator.hasNext()) {
			monitor.checkCanceled();
			Reference ref = referenceIterator.next();
			Address fromAddress = ref.getFromAddress();

			Function function = extendedFlatAPI.getFunctionContaining(fromAddress);
			if (function == null) {
				AddressSet subroutineAddresses =
					extendedFlatAPI.getSubroutineAddresses(program, fromAddress);
				Address minAddress = subroutineAddresses.getMinAddress();

				function = extendedFlatAPI.createFunction(minAddress, null);
				if (function == null) {
					continue;
				}
			}

			// get the decompiler highFunction
			HighFunction highFunction = decompilerUtils.getHighFunction(function);

			if (highFunction == null) {
				continue;
			}

			Iterator<PcodeOpAST> pcodeOps = highFunction.getPcodeOps(fromAddress);
			while (pcodeOps.hasNext()) {
				monitor.checkCanceled();
				PcodeOpAST pcodeOp = pcodeOps.next();
				int opcode = pcodeOp.getOpcode();
				if (opcode == PcodeOp.CALL) {
					Varnode input = pcodeOp.getInput(1);
					if (input == null) {
						continue;
					}
					Address callingAddress = input.getPCAddress();
					if (callingAddress.equals(Address.NO_ADDRESS)) {
						continue;
					}

					Address calledAddress =
						decompilerUtils.getCalledAddressFromCallingPcodeOp(input);

					if (calledAddress == null) {
						continue;
					}

					Function calledFunction = extendedFlatAPI.getFunctionAt(calledAddress);
					if (calledFunction == null) {
						calledFunction = extendedFlatAPI.createFunction(calledAddress, null);
						if (calledFunction == null) {
							continue;
						}

						if (!atexitCalledFunctions.contains(calledFunction)) {
							atexitCalledFunctions.add(calledFunction);
						}
						calledFunction.setReturnType(new VoidDataType(), SourceType.ANALYSIS);
					}
					else {
						if (!atexitCalledFunctions.contains(calledFunction)) {
							atexitCalledFunctions.add(calledFunction);
						}
					}

				}

			}

		}

	}

	/**
	 * Method to find deleting destructors that do one of the following:
	 * 	1. reference their own vftable (ie on own c/d list) which means function
	 *       is both a deleting destructor and has inlined the class destructor
	 *   2. reference their parent vftable (ie on parent c/d list) which means function
	 *       is a deleting destructor for class and inlined destructor for parent class
	 *   3. do not reference a vftable but call own destructor (call func on own c/d list) which
	 *       means it is just a deleting destructor for class but has no inlined destructor
	 * @param recoveredClass the given class
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if issues setting return type
	 */
	private boolean processDeletingDestructor(RecoveredClass recoveredClass, Function vfunction)
			throws CancelledException, DuplicateNameException, InvalidInputException {

		// if the virtual function IS ALSO on the class constructor/destructor list
		// then it is a deleting destructor with and inlined destructor and we need to 
		// determine if the inline is the class or parent/grandparent class destructor
		boolean isDeletingDestructor = false;
		if (getAllClassFunctionsWithVtableRef().contains(vfunction)) {

			recoveredClass.addDeletingDestructor(vfunction);
			recoveredClass.removeFromConstructorDestructorList(vfunction);
			recoveredClass.removeIndeterminateConstructorOrDestructor(vfunction);

			List<Address> vftableReferences = getVftableReferences(vfunction);
			if (vftableReferences == null) {
				return false;
			}
			Iterator<Address> vftableReferencesIterator = vftableReferences.iterator();
			while (vftableReferencesIterator.hasNext()) {
				monitor.checkCanceled();
				Address vftableReference = vftableReferencesIterator.next();
				Address vftableAddress = getVftableAddress(vftableReference);
				if (vftableAddress == null) {
					continue;
				}
				// Type 1
				if (recoveredClass.getVftableAddresses().contains(vftableAddress)) {
					recoveredClass.addInlinedDestructor(vfunction);

					isDeletingDestructor = true;
				}
				// Type 2
				else {
					RecoveredClass parentClass = getVftableClass(vftableAddress);
					parentClass.addInlinedDestructor(vfunction);

					parentClass.removeFromConstructorDestructorList(vfunction);
					parentClass.removeIndeterminateConstructorOrDestructor(vfunction);
					isDeletingDestructor = true;
				}
			}

		}
		// else, if the virtual function CALLS a function on the current class constructor/destructor list
		// then it is a deleting destructor and we have identified a destructor function for the class
		else {
			List<Function> classConstructorOrDestructorFunctions =
				recoveredClass.getConstructorOrDestructorFunctions();

			Iterator<Function> functionIterator = classConstructorOrDestructorFunctions.iterator();
			while (functionIterator.hasNext()) {
				monitor.checkCanceled();

				Function function = functionIterator.next();

				if (extendedFlatAPI.doesFunctionACallFunctionB(vfunction, function)) {
					recoveredClass.addDeletingDestructor(vfunction);

					addDestructorToClass(recoveredClass, function);

					recoveredClass.removeIndeterminateConstructorOrDestructor(function);
					isDeletingDestructor = true;
				}
			}
		}
		return isDeletingDestructor;

	}

	/**
	 * Use the known parent class(es)to determine which possible constructor destructor
	 * functions are constructors and which are destructors
	 * @param recoveredClasses List of RecoveredClass objects
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if issues setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 */
	public void processRegularConstructorsAndDestructorsUsingCallOrder(
			List<RecoveredClass> recoveredClasses) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();

			List<RecoveredClass> parentsToProcess = recoveredClass.getParentList();

			if (parentsToProcess.isEmpty()) {
				continue;
			}

			Iterator<RecoveredClass> parentsToProcessIterator = parentsToProcess.iterator();

			while (parentsToProcessIterator.hasNext()) {

				monitor.checkCanceled();

				RecoveredClass parentToProcess = parentsToProcessIterator.next();
				processConstructorsAndDestructorsUsingParent(recoveredClass, parentToProcess);
			}
		}

	}

	/**
	 * Use known ancestor class constructors and destructors to help classify indeterminate ones
	 * by who they call, ie constructors call parent (or grandparent) constructors and destructors
	 * call parent (or grandparent) destructors so use this to help figure out if the given class's
	 * indeterminate functions are constructors or destructors.
	 * @param recoveredClasses List of class objects
	 * @throws CancelledException if cancelled
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if error setting return type
	 */
	public void findConstructorsAndDestructorsUsingAncestorClassFunctions(
			List<RecoveredClass> recoveredClasses) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();

			List<Function> indeterminateList = recoveredClass.getIndeterminateList();
			if (indeterminateList.isEmpty()) {
				continue;
			}

			List<Function> allAncestorConstructors = getAllAncestorConstructors(recoveredClass);
			List<Function> allAncestorDestructors = getAncestorDestructors(recoveredClass);

			Iterator<Function> indeterminateIterator = indeterminateList.iterator();
			while (indeterminateIterator.hasNext()) {

				monitor.checkCanceled();

				Function indeterminateFunction = indeterminateIterator.next();

				List<Function> possibleAncestorConstructors =
					getPossibleParentConstructors(indeterminateFunction);
				Function ancestorConstructor =
					getFunctionOnBothLists(possibleAncestorConstructors, allAncestorConstructors);

				List<Function> possibleAncestorDestructors =
					getPossibleParentDestructors(indeterminateFunction);
				Function ancestorDestructor =
					getFunctionOnBothLists(possibleAncestorDestructors, allAncestorDestructors);

				// skip if both null - no results
				if (ancestorConstructor == null && ancestorDestructor == null) {
					continue;
				}

				// skip if neither null - conflicting results
				if (ancestorConstructor != null && ancestorDestructor != null) {
					continue;
				}

				if (ancestorConstructor != null) {
					addConstructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
				}

				if (ancestorDestructor != null) {
					addDestructorToClass(recoveredClass, indeterminateFunction);
					indeterminateIterator.remove();
				}
			}
		}

	}

	/**
	 * Method to classify indeterminate inline functions as either constructors or destructors
	 * using called ancestor information (may call parent or higher ancestor) or might be the same
	 * as a descendant constructor/destructor (ie a parent or ancestor is inlined into an
	 * indeterminate function so the same function is on both the parent inline list and the
	 * descendant regular list.
	 * @param recoveredClasses list of classes
	 * @throws CancelledException if cancelled
	 * @throws CircularDependencyException if parent namespace is descendent of given namespace
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 * @throws InvalidInputException if error setting return type
	 */
	public void findInlineConstructorsAndDestructorsUsingRelatedClassFunctions(
			List<RecoveredClass> recoveredClasses) throws CancelledException, InvalidInputException,
			DuplicateNameException, CircularDependencyException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();

			List<Function> indeterminateList =
				new ArrayList<Function>(recoveredClass.getIndeterminateInlineList());

			if (indeterminateList.isEmpty()) {
				continue;
			}

			List<Function> allRelatedConstructors = getAllAncestorConstructors(recoveredClass);
			List<Function> allRelatedDestructors = getAncestorDestructors(recoveredClass);

			Iterator<Function> indeterminateIterator = indeterminateList.iterator();
			while (indeterminateIterator.hasNext()) {
				monitor.checkCanceled();
				Function indeterminateFunction = indeterminateIterator.next();

				// get the addresses in the function that refer to classes either by
				// referencing a vftable in a class or by calling a function in a class
				Map<Address, RecoveredClass> referenceToClassMap =
					getReferenceToClassMap(recoveredClass, indeterminateFunction);

				List<Function> allDescendantConstructors =
					getAllDescendantConstructors(recoveredClass);
				if (allDescendantConstructors.contains(indeterminateFunction)) {
					processInlineConstructor(recoveredClass, indeterminateFunction,
						referenceToClassMap);
					continue;
				}

				List<Function> allDescendantDestructors =
					getAllDescendantDestructors(recoveredClass);
				if (allDescendantDestructors.contains(indeterminateFunction)) {
					processInlineDestructor(recoveredClass, indeterminateFunction,
						referenceToClassMap);
					continue;
				}

				List<Function> possibleAncestorConstructors =
					getPossibleParentConstructors(indeterminateFunction);

				Function ancestorConstructor =
					getFunctionOnBothLists(possibleAncestorConstructors, allRelatedConstructors);

				List<Function> possibleAncestorDestructors =
					getPossibleParentDestructors(indeterminateFunction);
				Function ancestorDestructor =
					getFunctionOnBothLists(possibleAncestorDestructors, allRelatedDestructors);

				// skip if both null - no results
				if (ancestorConstructor == null && ancestorDestructor == null) {
					continue;
				}

				// skip if both null - conflicting results
				if (ancestorConstructor != null && ancestorDestructor != null) {
					continue;
				}

				if (ancestorConstructor != null) {
					processInlineConstructor(recoveredClass, indeterminateFunction,
						referenceToClassMap);
					continue;
				}

				if (ancestorDestructor != null) {
					processInlineDestructor(recoveredClass, indeterminateFunction,
						referenceToClassMap);
					continue;
				}

			}
		}

	}

	/**
	 * Method to find destructors using functions called by atexit. If they are on the list of
	 * indeterminate constructors or destructors and are called by atexit, then they are a
	 * destructor.
	 * @param recoveredClasses list of classes
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if error setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void findDestructorsUsingAtexitCalledFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException, InvalidInputException, DuplicateNameException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();

			List<Function> indeterminateList = recoveredClass.getIndeterminateList();

			Iterator<Function> indeterminateIterator = indeterminateList.iterator();
			while (indeterminateIterator.hasNext()) {
				monitor.checkCanceled();
				Function indeterminateFunction = indeterminateIterator.next();
				if (atexitCalledFunctions.contains(indeterminateFunction)) {
					recoveredClass.addNonThisDestructor(indeterminateFunction);
					indeterminateIterator.remove();
				}
			}

			List<Function> indeterminateInlineList = recoveredClass.getIndeterminateInlineList();

			Iterator<Function> indeterminateInlineIterator = indeterminateInlineList.iterator();
			while (indeterminateInlineIterator.hasNext()) {
				monitor.checkCanceled();
				Function indeterminateFunction = indeterminateInlineIterator.next();
				if (atexitCalledFunctions.contains(indeterminateFunction)) {
					addInlinedDestructorToClass(recoveredClass, indeterminateFunction);
					indeterminateInlineIterator.remove();
				}
			}
		}
	}

	/**
	 * Method to find deleting destructors in the virtual functions of the given list of classes. 
	 * Method finds the following use cases (Note that in all cases operator_delete is called after
	 *  the called destructor or inlined destructor):
	 *    1. deleting destructors that reference their own vftable (ie on own c/d list) which means
	 *    function is both a deleting destructor and has an inlined class destructor.
	 *    2. deleting destructors that references a parent vftable (ie on parent c/d list) which
	 *    means function is a deleting destructor in the class and contains an inlined destructor
	 *    for the parent class.
	 *    3. deleting destructors that do not reference a vftable but call their own destructor
	 *    4. deleting destructors that do not reference a vftable with only two calls and that calls
	 *      a destructor from another class
	 *    5. deleting destructors that do not reference a vftable with only two calls tand that 
	 *      calls a vbase destructor
	 * @param recoveredClasses the given classes to process for deleting destructors
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if duplicate name
	 * @throws InvalidInputException  if invalid input
	 */
	public void findDeletingDestructors(List<RecoveredClass> recoveredClasses)
			throws CancelledException, InvalidInputException, DuplicateNameException {

		if (recoveredClasses.isEmpty()) {
			return;
		}

		// try finding operator delete functions by name or if can't, by common function calls
		List<Function> operatorDeletesList = getOperatorDeleteFunctions(recoveredClasses);
		if (operatorDeletesList == null) {
			return;
		}

		// iterate over all class virtual functions to find the ones that are deleting destructors
		for (RecoveredClass recoveredClass : recoveredClasses) {
			monitor.checkCanceled();

			List<Function> vfunctions = recoveredClass.getAllVirtualFunctions();

			if (vfunctions.isEmpty()) {
				continue;
			}

			for (Function vfunction : vfunctions) {
				monitor.checkCanceled();

				// get the map of addresses in vfunction to the corresponding functions called
				// from those addresses
				Map<Address, Function> addressToFunctionCallMap =
					getAddressToFunctionCallMap(vfunction);

				if (addressToFunctionCallMap == null) {
					continue;
				}

				List<Address> operatorDeleteCallingAddresses = getAddressesOfListedFunctionsInMap(
					operatorDeletesList, addressToFunctionCallMap);

				// only process vfunctions with at least one call to operator delete
				if (operatorDeleteCallingAddresses.size() == 0) {
					continue;
				}

				// get the first call to operator delete in the function 
				Collections.sort(operatorDeleteCallingAddresses);

				// get first vftable reference in vfuntion
				List<Address> vftableReferences = getVftableReferences(vfunction);
				Address firstVftableReference = null;
				if (vftableReferences != null) {
					Collections.sort(vftableReferences);
					firstVftableReference = vftableReferences.get(0);
				}

				List<Function> possibleCalledDestructors =
					getPossibleCalledDestructors(addressToFunctionCallMap,
						operatorDeleteCallingAddresses, firstVftableReference);

				// process deleting destructors if type 1, 2 or 3
				boolean isDeletingDestructor = processDeletingDestructor(recoveredClass, vfunction);
				if (isDeletingDestructor) {
					processPossibleDestructors(possibleCalledDestructors, vfunction);
					continue;
				}

				// process deleting destructors type 4 and 5
				// if function has only two calls and one is a vetted possible destructor (ie on 
				// list called after first vftable reference and  before operator delete) and the 
				// other is a call to operator delete, then it is one of these two types
				if (!allClassFunctionsContainingVftableReference.contains(vfunction) &&
					operatorDeleteCallingAddresses.size() == 1 &&
					addressToFunctionCallMap.keySet().size() == 2 &&
					possibleCalledDestructors.size() == 1) {

					recoveredClass.addDeletingDestructor(vfunction);
					Function destructor = possibleCalledDestructors.get(0);

					// if the called destructor isn't on the possible constructor/destructor 
					// list then it is a vbase destructor
					if (firstVftableReference == null &&
						!allPossibleConstructorsAndDestructors.contains(destructor)) {

						recoveredClass.setVBaseDestructor(destructor);
						continue;
					}

					// else, if the vfunction CALLS a function on the constructor/destructor list
					// it is a regular destructor for some class
					if (allPossibleConstructorsAndDestructors.contains(destructor)) {
						processFoundDestructor(destructor, vfunction);
					}
				}

			}
		}

	}


	/**
	 * Method to find the operator delete functions in the current program either by name or
	 * by common virtual function calls
	 * @return a list of operator_delete functions or null if none could be determined
	 * @throws CancelledException if cancelled
	 */
	private List<Function> getOperatorDeleteFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		// try finding operator delete functions by name or if can't, by common function calls
		Set<Function> operatorDeletes = findOperatorDeletes();
		if (operatorDeletes.isEmpty()) {
			operatorDeletes = findOperatorDeletesUsingCalledCommonFunction(recoveredClasses);
			if (operatorDeletes.isEmpty()) {
				return null;
			}
		}

		List<Function> operatorDeletesList = new ArrayList<Function>(operatorDeletes);
		return operatorDeletesList;
	}

	/**
	 * Method to find the operator delete functions in the current program either by name or
	 * by common virtual function calls
	 * @return a list of operator_delete functions or null if none could be determined
	 * @throws CancelledException if cancelled
	 */
	private List<Function> getOperatorNewFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		// try finding operator delete functions by name or if can't, by common function calls
		Set<Function> operatorNews = findOperatorNews();
		if (operatorNews.isEmpty()) {
			operatorNews = findOperatorNewsUsingCalledCommonFunction(recoveredClasses);
			if (operatorNews.isEmpty()) {
				return null;
			}
		}

		List<Function> operatorNewsList = new ArrayList<Function>(operatorNews);
		return operatorNewsList;
	}

	private List<Function> getPossibleCalledDestructors(
			Map<Address, Function> addressToFunctionCallMap,
			List<Address> operatorDeleteCallingAddresses, Address firstVftableReference)
			throws CancelledException {

		Set<Function> possibleCalledDestructorSet = new HashSet<Function>();

		List<Address> callingAddresses = new ArrayList<Address>(addressToFunctionCallMap.keySet());

		callingAddresses.removeAll(operatorDeleteCallingAddresses);

		Collections.sort(operatorDeleteCallingAddresses);
		Address operatorDeleteCallingAddress = operatorDeleteCallingAddresses.get(0);

		Collections.sort(callingAddresses);
		for (Address callingAddress : callingAddresses) {
			monitor.checkCanceled();

			// skip if the function call is after the operator delete
			if (callingAddress.getOffset() > operatorDeleteCallingAddress.getOffset()) {
				continue;
			}

			// skip if there is a vftableref and it is after the function call
			if (firstVftableReference != null &&
				firstVftableReference.getOffset() > callingAddress.getOffset()) {
				continue;
			}

			// add the called function to the list if it is between the vftable ref and
			// the operator delete or if there is no vftable ref, the call is before
			// the operator delete call
			Function calledFunction = addressToFunctionCallMap.get(callingAddress);
			if (calledFunction.isThunk()) {
				calledFunction.getThunkedFunction(true);
			}
			possibleCalledDestructorSet.add(calledFunction);
		}
		List<Function> possibleCalledDestructors =
			new ArrayList<Function>(possibleCalledDestructorSet);
		return possibleCalledDestructors;
	}


	private void processPossibleDestructors(List<Function> possibleDestructors,
			Function calledFromFunction)
			throws InvalidInputException, DuplicateNameException, CancelledException {

		if (possibleDestructors.isEmpty()) {
			return;
		}

		for (Function possibleDestructor : possibleDestructors) {
			monitor.checkCanceled();
			if (allPossibleConstructorsAndDestructors.contains(possibleDestructor)) {
				processFoundDestructor(possibleDestructor, calledFromFunction);
			}
		}
	}

	private void processFoundDestructor(Function possibleDestructor, Function calledFromFunction)
			throws InvalidInputException, DuplicateNameException {

		List<RecoveredClass> classes = getClasses(possibleDestructor);
		if (classes.size() == 1) {
			RecoveredClass recoveredClass = classes.get(0);
			addDestructorToClass(recoveredClass, possibleDestructor);
			bookmarkAddress(possibleDestructor.getEntryPoint(),
				recoveredClass.getName() +
					" destructor found using last new called method - called from dd at " +
					calledFromFunction.getEntryPoint(),
				"TEST RC");
			recoveredClass.removeIndeterminateConstructorOrDestructor(possibleDestructor);
		}
	}


	/**
	 * Method to return list of reference addresses for any of the given functions that are 
	 * contained in the given map
	 * @param functions the given functions
	 * @param addressToCalledFunctions the given map of addresses to called functions
	 * @return a list of function addresses for functions that are on both the given function list 
	 * and in the given map entry set
	 * @throws CancelledException if cancelled
	 */
	private List<Address> getAddressesOfListedFunctionsInMap(List<Function> functions,
			Map<Address, Function> addressToCalledFunctions) throws CancelledException {

		List<Address> calledFunctionsOnList = new ArrayList<Address>();
		Set<Address> addresses = addressToCalledFunctions.keySet();
		for (Address address : addresses) {
			monitor.checkCanceled();

			Function function = addressToCalledFunctions.get(address);
			if (functions.contains(function)) {
				calledFunctionsOnList.add(address);
			}
		}
		return calledFunctionsOnList;
	}

	/**
	 * Find operator_delete functions (including thunks) by name or by using calls to various 
	 * named "free" functions
	 * @return a Set of operator_delete functions
	 * @throws CancelledException if cancelled
	 */
	public Set<Function> findOperatorDeletes() throws CancelledException {

		Set<Function> operatorDeletesBySymbol = findFunctionsUsingSymbolName("operator_delete");

		if (!operatorDeletesBySymbol.isEmpty()) {
			return operatorDeletesBySymbol;
		}

		Set<Function> operatorDeletesByThunkToFree = new HashSet<Function>();

		Set<Function> freesBySymbol = findFunctionsUsingSymbolName("_free");
		Set<Function> freebasesBySymbol = findFunctionsUsingSymbolName("_free_base");

		freesBySymbol.addAll(freebasesBySymbol);

		if (!freesBySymbol.isEmpty()) {
			for (Function function : freesBySymbol) {
				monitor.checkCanceled();

				Set<Function> thunksTo = getThunksTo(function);
				operatorDeletesByThunkToFree.addAll(thunksTo);
			}
		}

		if (!operatorDeletesByThunkToFree.isEmpty()) {
			return operatorDeletesByThunkToFree;
		}

		// look for external free call
		Set<Function> operatorDeletesByThunkToExternalFree = new HashSet<Function>();
		Set<Function> externalFrees = findFunctionsUsingSymbolName("free");
		if (!externalFrees.isEmpty()) {
			for (Function externalFree : externalFrees) {
				if (!externalFree.isExternal()) {
					continue;
				}

				// get all thunks to external free
				Address[] thunksToExternalFree = externalFree.getFunctionThunkAddresses(true);
				if (thunksToExternalFree == null) {
					continue;
				}
				for (Address address : thunksToExternalFree) {
					monitor.checkCanceled();

					Function function = api.getFunctionAt(address);
					operatorDeletesByThunkToExternalFree.add(function);
					Set<Function> thunksToThunk = getThunksTo(function);
					if (thunksToThunk.isEmpty()) {
						continue;
					}
					for (Function thunkToExternalFree : thunksToThunk) {
						monitor.checkCanceled();
						// in this case just want the true thunks not the single call functions - get unwinds that way
						if (thunkToExternalFree.isThunk()) {
							operatorDeletesByThunkToExternalFree.add(thunkToExternalFree);
						}
					}
				}

			}
		}
		return operatorDeletesByThunkToExternalFree;
	}

	/**
	 * Find operator_new functions (including thunks) by name or by using calls to various 
	 * named "new" or "malloc" functions
	 * @return a Set of operator_new functions
	 * @throws CancelledException if cancelled
	 */
	public Set<Function> findOperatorNews() throws CancelledException {

		Set<Function> operatorDeletesBySymbol = findFunctionsUsingSymbolName("operator_new");

		if (!operatorDeletesBySymbol.isEmpty()) {
			return operatorDeletesBySymbol;
		}

		// if can't find it by name, look for function with call to malloc then __callnewh
		Set<Function> operatorNewsByCallToMallocAndCallnewh = new HashSet<Function>();

		Set<Function> callnewhBySymbol = findFunctionsUsingSymbolName("__callnewh");
		callnewhBySymbol.addAll(findFunctionsUsingSymbolName("_callnewh"));

		Set<Function> mallocBySymbol = findFunctionsUsingSymbolName("malloc");

		// return emtpy list if no methods called __callnewh
		if (callnewhBySymbol.isEmpty()) {
			return operatorNewsByCallToMallocAndCallnewh;
		}

		if (mallocBySymbol.isEmpty()) {
			return operatorNewsByCallToMallocAndCallnewh;
		}

		Set<Function> functionsThatCallCallnewh = new HashSet<Function>();
		for (Function callnewh : callnewhBySymbol) {
			monitor.checkCanceled();

			Set<Function> callingFunctions =
				new HashSet<Function>(callnewh.getCallingFunctions(monitor));
			functionsThatCallCallnewh.addAll(callingFunctions);
		}

		for (Function malloc : mallocBySymbol) {
			monitor.checkCanceled();

			List<Function> callingFunctions =
				new ArrayList<Function>(malloc.getCallingFunctions(monitor));

			if (callingFunctions.isEmpty()) {
				continue;
			}
			for (Function callingFunction : callingFunctions) {
				monitor.checkCanceled();
				if (functionsThatCallCallnewh.contains(callingFunction) &&
					!operatorNewsByCallToMallocAndCallnewh.contains(callingFunction)) {
					operatorNewsByCallToMallocAndCallnewh.add(callingFunction);
				}
			}

		}

		return operatorNewsByCallToMallocAndCallnewh;
	}

	private Set<Function> findFunctionsUsingSymbolName(String name) throws CancelledException {

		Set<Function> functions = new HashSet<Function>();

		FunctionManager functionManager = program.getFunctionManager();

		SymbolIterator symbolIterator = symbolTable.getSymbolIterator();
		while (symbolIterator.hasNext()) {
			monitor.checkCanceled();
			Symbol symbol = symbolIterator.next();
			if (!symbol.getName().equals(name)) {
				continue;
			}
			Address address = symbol.getAddress();

			Function function = functionManager.getFunctionAt(address);
			if (function != null) {
				functions.add(function);
			}
		}
		return functions;
	}

	private Set<Function> getThunksTo(Function function) throws CancelledException {

		Set<Function> thunksToFunction = new HashSet<Function>();
		if (function == null) {
			return thunksToFunction;
		}

		Reference[] referencesTo = api.getReferencesTo(function.getEntryPoint());

		if (referencesTo == null || referencesTo.length == 0) {
			return thunksToFunction;
		}

		for (Reference ref : referencesTo) {
			monitor.checkCanceled();
			Address address = ref.getFromAddress();

			Instruction instruction = program.getListing().getInstructionAt(address);
			if (instruction == null) {
				continue;
			}

			Function thunkFunction = api.getFunctionContaining(address);
			if (thunkFunction == null) {
				continue;
			}

			boolean isThunk = false;
			if (instruction.getFlowType() == RefType.UNCONDITIONAL_JUMP &&
				thunkFunction.isThunk()) {
				isThunk = true;
			}
			if (!isThunk) {
				FlowOverride flowOverride = instruction.getFlowOverride();
				if (flowOverride.equals(FlowOverride.CALL) ||
					flowOverride.equals(FlowOverride.CALL_RETURN)) {

					// skip any that call more than one function to limit to only possible
					// thunks
					if (thunkFunction.getCalledFunctions(monitor).size() > 1) {
						continue;
					}
					isThunk = true;
				}
			}

			if (isThunk) {
				thunksToFunction.add(thunkFunction);

				// add thunks to this function too
				Set<Function> thunksToThunk = getThunksTo(thunkFunction);
				if (thunksToThunk.isEmpty()) {
					continue;
				}

				for (Function thunk : thunksToThunk) {
					monitor.checkCanceled();

					thunksToFunction.add(thunk);
				}

			}
		}
		return thunksToFunction;
	}

	/**
	 * Figure out which of the destructors that do not reference vftable are vbase destructors and
	 * which are destructors.
	 * @param recoveredClasses List of RecoveredClass objects
	 * @throws CancelledException if cancelled
	 * @throws InvalidInputException if error setting return type
	 * @throws DuplicateNameException if try to create same symbol name already in namespace
	 */
	public void findRealVBaseFunctions(List<RecoveredClass> recoveredClasses)
			throws CancelledException, InvalidInputException, DuplicateNameException {

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();
			RecoveredClass recoveredClass = recoveredClassIterator.next();
			Function vBaseDestructor = recoveredClass.getVBaseDestructor();
			if (vBaseDestructor == null) {
				continue;
			}
			if (!hasVbaseDestructor(recoveredClass)) {
				addDestructorToClass(recoveredClass, vBaseDestructor);
				recoveredClass.setVBaseDestructor(null);
			}

		}
	}

	public void makeConstructorsAndDestructorsThiscalls(List<RecoveredClass> recoveredClasses)
			throws CancelledException, InvalidInputException, DuplicateNameException {

		if (recoveredClasses.isEmpty()) {
			return;
		}

		List<Function> allConstructorDestructorFunctions = new ArrayList<Function>();

		for (RecoveredClass recoveredClass : recoveredClasses) {

			monitor.checkCanceled();

			allConstructorDestructorFunctions.addAll(recoveredClass.getConstructorList());
			allConstructorDestructorFunctions.addAll(recoveredClass.getDestructorList());
			allConstructorDestructorFunctions.addAll(recoveredClass.getInlinedConstructorList());
			allConstructorDestructorFunctions.addAll(recoveredClass.getInlinedDestructorList());
		}

		if (allConstructorDestructorFunctions.isEmpty()) {
			return;
		}

		// remove the inlines that are not in their expected class -- still want the inline
		// comments later in processing but don't make them this calls
		allConstructorDestructorFunctions.removeAll(nonClassInlines);

		for (Function function : allConstructorDestructorFunctions) {
			monitor.checkCanceled();
			makeFunctionThiscall(function);
		}

	}

	/**
	 * Method to create <class_name>_data structure for given class
	 * @param recoveredClass the class
	 * @param classStructure the given class structure
	 * @param dataLen the length of data
	 * @param dataOffset the offset of data
	 * @return the <class_name>_data structure containing the given classes data members
	 * @throws CancelledException if cancelled
	 */
	public Structure createClassMemberDataStructure(RecoveredClass recoveredClass,
			Structure classStructure, int dataLen, int dataOffset) throws CancelledException {

		Structure classDataStructure = new StructureDataType(recoveredClass.getClassPath(),
			recoveredClass.getName() + CLASS_DATA_STRUCT_NAME, dataLen, dataTypeManager);

		Structure computedClassDataStructure;

		if (recoveredClass.hasExistingClassStructure()) {
			computedClassDataStructure = recoveredClass.getExistingClassStructure();
		}
		else {
			computedClassDataStructure = recoveredClass.getComputedClassStructure();
		}

		if (computedClassDataStructure == null || computedClassDataStructure.getLength() == 0) {
			classDataStructure = (Structure) dataTypeManager.addDataType(classDataStructure,
				DataTypeConflictHandler.DEFAULT_HANDLER);
			return classDataStructure;
		}

		DataTypeComponent[] definedComponents = computedClassDataStructure.getDefinedComponents();

		for (DataTypeComponent definedComponent : definedComponents) {

			monitor.checkCanceled();

			int offset = definedComponent.getOffset() - dataOffset;

			// skip any components that have offset less than the skip length
			// for classes without parents the skip is in general just the vftable ptr length
			// for classes with parents the skip is the entire parent(s) length
			if (offset < 0) {
				continue;
			}

			if (offset >= dataLen) {
				continue;
			}

			DataType dataType = definedComponent.getDataType();
			if (dataType == DataType.DEFAULT) {
				dataType = new Undefined1DataType();
			}

			String fieldName = new String();
			String comment = null;

			// if the computed class struct has field name (ie from pdb) use it otherwise create one
			if (definedComponent.getFieldName() == null) {
				fieldName = "offset_" + extendedFlatAPI.toHexString(offset, false, true);
			}
			else {
				fieldName = definedComponent.getFieldName();
				comment = definedComponent.getComment();
			}

			classDataStructure.replaceAtOffset(offset, dataType, dataType.getLength(), fieldName,
				comment);
		}

		// only set alignment if all the offsets have been accounted for
		if (classDataStructure.getNumComponents() == classDataStructure.getNumDefinedComponents()) {
			classDataStructure.setPackingEnabled(true);
		}
		classDataStructure = (Structure) dataTypeManager.addDataType(classDataStructure,
			DataTypeConflictHandler.DEFAULT_HANDLER);

		return classDataStructure;
	}

	/**
	 * Method to find the purecall function.
	 * @param recoveredClasses List of RecoveredClass objects
	 * @throws CancelledException when cancelled
	 * @throws Exception when issue making label
	 */
	public void identifyPureVirtualFunction(List<RecoveredClass> recoveredClasses)
			throws CancelledException, Exception {

		Function possiblePureCall = null;

		Iterator<RecoveredClass> recoveredClassIterator = recoveredClasses.iterator();

		while (recoveredClassIterator.hasNext()) {
			monitor.checkCanceled();

			RecoveredClass recoveredClass = recoveredClassIterator.next();
			if (recoveredClass.hasChildClass()) {
				Function sameFunction = null;
				List<Function> deletingDestructors = recoveredClass.getDeletingDestructors();
				List<Function> virtualFunctions = recoveredClass.getAllVirtualFunctions();
				if (virtualFunctions.size() < 3) {
					continue;
				}
				Iterator<Function> vfunctionIterator = virtualFunctions.iterator();
				while (vfunctionIterator.hasNext()) {
					monitor.checkCanceled();
					Function vfunction = vfunctionIterator.next();
					// skip the deleting destructors
					if (deletingDestructors.contains(vfunction)) {
						continue;
					}
					if (sameFunction == null) {
						sameFunction = vfunction;
					}
					else if (!sameFunction.equals(vfunction)) {
						sameFunction = null;
						break;
					}

				}

				if (sameFunction == null) {
					continue;
				}

				// if we didn't already assign it, do it here
				if (possiblePureCall == null) {
					possiblePureCall = sameFunction;
				}
				// if they ever don't match return
				else if (!possiblePureCall.equals(sameFunction)) {
					if (DEBUG) {
						Msg.debug(this, "Could not identify pure call. ");
					}
					return;
				}
			}
		}

		// If we get this far then we are sure the purecall function
		// is correct so assign the global variable to it
		if (possiblePureCall != null) {
			purecall = possiblePureCall;
			if (possiblePureCall.getSymbol().getSource() == SourceType.DEFAULT) {
				Msg.debug(this, "Found unlabeled purecall function: " +
					possiblePureCall.getEntryPoint().toString() + ". Creating label there.");
				api.createLabel(possiblePureCall.getEntryPoint(), "purecall", true);
			}
		}
	}

	/**
	 * Method to remove duplicate functions from the given list
	 * @param list the given list of functions
	 * @return the deduped list of functions
	 * @throws CancelledException if cancelled
	 */
	public List<Function> removeDuplicateFunctions(List<Function> list) throws CancelledException {

		List<Function> listOfUniqueFunctions = new ArrayList<Function>();

		Iterator<Function> listIterator = list.iterator();
		while (listIterator.hasNext()) {
			monitor.checkCanceled();
			Function function = listIterator.next();
			if (!listOfUniqueFunctions.contains(function)) {
				listOfUniqueFunctions.add(function);
			}
		}
		return listOfUniqueFunctions;
	}

	/**
	 * Method to remove duplicate addresses from the given list
	 * @param list the given list of functions
	 * @return the deduped list of functions
	 * @throws CancelledException if cancelled
	 */
	public List<Address> removeDuplicateAddresses(List<Address> list) throws CancelledException {

		List<Address> listOfUniqueAddresses = new ArrayList<Address>();

		Iterator<Address> listIterator = list.iterator();
		while (listIterator.hasNext()) {
			monitor.checkCanceled();
			Address address = listIterator.next();
			if (!listOfUniqueAddresses.contains(address)) {
				listOfUniqueAddresses.add(address);
			}
		}
		return listOfUniqueAddresses;
	}

	/**
	 * Remove functions that are on both vfunction and cd list from the cd lists and add the rest to
	 * a more restrictive list
	 * @param recoveredClasses List of RecoveredClass objects
	 * @throws CancelledException if cancelled
	 */
	protected void trimConstructorDestructorLists(List<RecoveredClass> recoveredClasses)
			throws CancelledException {

		if (recoveredClasses.isEmpty()) {
			return;
		}

		for (RecoveredClass recoveredClass : recoveredClasses) {

			monitor.checkCanceled();

			List<Function> constructorOrDestructorFunctions =
				recoveredClass.getConstructorOrDestructorFunctions();

			if (constructorOrDestructorFunctions.isEmpty()) {
				continue;
			}

			Iterator<Function> cdFunctionIterator = constructorOrDestructorFunctions.iterator();
			while (cdFunctionIterator.hasNext()) {

				monitor.checkCanceled();

				Function cdFunction = cdFunctionIterator.next();

				if (!getAllVfunctions().contains(cdFunction)) {
					updateAllPossibleConstructorDestructorList(cdFunction);
					continue;
				}

				// straight cd function will not also be a vfunction so remove it from the given 
				// class cd list
				cdFunctionIterator.remove();
				recoveredClass.removeIndeterminateConstructorOrDestructor(cdFunction);
			}
		}

	}

	/**
	 * Method to apply the function signature of the given function, if different, to the corresponding
	 * function definition and call the method to update related structure fields and functions
	 * @param vfunction the given function
	 * @param vftableAddress the vftable address of a vftable containing the function
	 * @return a list of changed items
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if duplicate name exception while making changes
	 * @throws DataTypeDependencyException if data type dependency exception while making changes
	 * @throws InvalidInputException if invalid input while making changes
	 */
	public List<Object> applyNewFunctionSignature(Function vfunction, Address vftableAddress)
			throws CancelledException, DuplicateNameException, DataTypeDependencyException,
			InvalidInputException {

		List<Object> changedItems = new ArrayList<Object>();

		Data vftableData = api.getDataAt(vftableAddress);

		Structure vfunctionStructure = getStructureFromData(vftableData);
		if (vfunction == null) {
			return null;
		}

		Category category = getDataTypeCategory(vfunctionStructure);

		if (category == null) {
			return null;
		}

		if (!category.getCategoryPath().getPath().startsWith(DTM_CLASS_DATA_FOLDER_PATH)) {
			return null;
		}

		if (!vfunctionStructure.getName().contains("_vftable")) {
			return null;
		}

		// get the corresponding structure component for the given vfunction in the vftable
		DataTypeComponent structureComponent = getVfunctionComponent(vftableData, vfunction);

		if (structureComponent == null) {
			throw new IllegalArgumentException(
				"Passed in function must be referenced by passed in vftable");
		}

		List<Object> newChangedItems =
			updateCorrespondingFunctionDefinition(vfunction, structureComponent);

		changedItems = updateList(changedItems, newChangedItems);

		return changedItems;
	}

	private Structure getStructureFromData(Data data) {
		if (data == null) {
			return null;
		}
		DataType baseDataType = data.getBaseDataType();
		if (!(baseDataType instanceof Structure)) {
			return null;
		}

		return (Structure) baseDataType;

	}

	/**
	 * Method to get the corresponding structure component given a function in the vftable
	 * @param vftableData the vftable data containing the vfunction
	 * @param vfunction the given vfunction
	 * @return the associated structure component of the given function in the given vftable data or
	 * null if no associated function can be found
	 * @throws IllegalArgumentException if the given Data is not a Structure
	 * @throws CancelledException if cancelled
	 */
	private DataTypeComponent getVfunctionComponent(Data vftableData, Function vfunction)
			throws CancelledException {

		// get the Structure data type from the given Data
		Structure vfunctionStructure = getStructureFromData(vftableData);
		if (vfunctionStructure == null) {
			throw new IllegalArgumentException(
				"The given Data argument must be a structure " + vftableData.toString());
		}

		int numComponents = vftableData.getNumComponents();
		for (int i = 0; i < numComponents; i++) {
			monitor.checkCanceled();

			// get the index of the DATA component that corresponds to the function
			Address functionPointerAddress = vftableData.getComponent(i).getAddress();

			Function function = extendedFlatAPI.getReferencedFunction(functionPointerAddress);

			// return the structure DATA TYPE component of the corresponding DATA component
			if (vfunction.equals(function)) {
				return vfunctionStructure.getComponent(i);
			}

		}
		return null;
	}

	/**
	 * Return a list of data items that have labels containing "vftable" and refer to the given
	 * function
	 * @param function the given function
	 * @return a list of vftable data addresses that contain references to the given function and
	 * have label containing text "vftable"
	 * @throws CancelledException if cancelled
	 */
	public List<Address> getVftablesContaining(Function function) throws CancelledException {

		ReferenceManager refMan = program.getReferenceManager();
		List<Address> vftableAddresses = new ArrayList<Address>();

		Address[] functionThunkAddresses = function.getFunctionThunkAddresses(true);

		// add any thunk addresses to the list
		List<Address> functionAddresses =
			new ArrayList<Address>(Arrays.asList(functionThunkAddresses));
		// add the function itself to the list
		functionAddresses.add(function.getEntryPoint());

		for (Address address : functionAddresses) {
			monitor.checkCanceled();

			ReferenceIterator referencesTo = refMan.getReferencesTo(address);

			while (referencesTo.hasNext()) {
				monitor.checkCanceled();

				Reference referenceTo = referencesTo.next();
				if (referenceTo.getReferenceType() != RefType.DATA) {
					continue;
				}

				Address ref = referenceTo.getFromAddress();

				Data dataContaining = api.getDataContaining(ref);
				if (dataContaining == null) {
					continue;
				}

				Address vftableAddress = dataContaining.getAddress();

				Symbol vftableSymbol = api.getSymbolAt(vftableAddress);
				if (!vftableSymbol.getName().contains("vftable")) {
					continue;
				}

				if (!vftableAddresses.contains(vftableAddress)) {
					vftableAddresses.add(vftableAddress);
				}
			}
		}

		return vftableAddresses;

	}

	/**
	 * Method to find any function signatures in the given vfunction structure that have changed
	 * and update the corresponding function definition data types
	 * @param vfunction the given virtual function
	 * @param structureComponent the associated virtual function structure component to update
	 * @return list of updated objects
	 * @throws DuplicateNameException if duplicate name
	 * @throws DataTypeDependencyException if data dependency exception
	 * @throws InvalidInputException if invalid input in setName of function
	 * @throws CancelledException if cancelled
	 */
	private List<Object> updateCorrespondingFunctionDefinition(Function vfunction,
			DataTypeComponent structureComponent) throws DuplicateNameException,
			DataTypeDependencyException, InvalidInputException, CancelledException {

		List<Object> changedItems = new ArrayList<Object>();

		// skip purecalls - do not want to update function defs or other related sigs
		if (vfunction.getName().contains("purecall")) {
			return null;
		}

		FunctionSignature listingFunctionSignature = vfunction.getSignature(true);

		FunctionDefinition componentFunctionDefinition =
			getComponentFunctionDefinition(structureComponent);
		if (componentFunctionDefinition == null) {
			return null;
		}

		FunctionDefinition newFunctionDefinition = (FunctionDefinition) listingFunctionSignature;

		if (!areEquivalentFunctionSignatures(componentFunctionDefinition,
			listingFunctionSignature)) {

			FunctionDefinition changedFunctionDefinition =
				editFunctionDefinition(structureComponent, newFunctionDefinition);

			if (changedFunctionDefinition == null) {
				return changedItems;
			}

			changedItems.add(changedFunctionDefinition);

			List<Object> newChangedItems = applyNewFunctionDefinition(changedFunctionDefinition);

			changedItems = updateList(changedItems, newChangedItems);
		}

		return changedItems;

	}

	private FunctionDefinition getComponentFunctionDefinition(
			DataTypeComponent structureComponent) {

		DataType componentDataType = structureComponent.getDataType();
		if (!(componentDataType instanceof Pointer)) {
			return null;
		}

		Pointer pointer = (Pointer) componentDataType;
		DataType pointedToDataType = pointer.getDataType();

		if (!(pointedToDataType instanceof FunctionDefinition)) {
			return null;
		}
		FunctionDefinition componentFunctionDefinition = (FunctionDefinition) pointedToDataType;
		return componentFunctionDefinition;
	}

	/**
	 * Method to edit the function definition pointed to by the given structure component with any
	 * differences in the given function definition
	 * @param structureComponent the given structure component
	 * @param newFunctionDefinition the given function definition
	 * @return the changed function definition or null if no changes are made
	 */
	private FunctionDefinition editFunctionDefinition(DataTypeComponent structureComponent,
			FunctionDefinition newFunctionDefinition) {

		DataType componentDataType = structureComponent.getDataType();
		if (!(componentDataType instanceof Pointer)) {
			throw new IllegalArgumentException("Structure component must be a pointer " +
				structureComponent.getDataType().getName());
		}

		Pointer pointer = (Pointer) componentDataType;
		DataType pointedToDataType = pointer.getDataType();

		if (!(pointedToDataType instanceof FunctionDefinition)) {
			throw new IllegalArgumentException(
				"Structure component must be a pointer to a FunctionDefinition " +
					structureComponent.getDataType().getName());
		}

		FunctionDefinition componentFunctionDefinition = (FunctionDefinition) pointedToDataType;

		if (!componentFunctionDefinition.isEquivalent(newFunctionDefinition)) {
			// if it is a purecall then don't update the function definition as it will always point
			// to the purecall function and we don't want to rename that function to the new name
			// since anyone calling purecall will call it
			if (!componentFunctionDefinition.getName().contains("purecall")) {
				// otherwise update data type with new new signature
				FunctionDefinition changedFunctionDefinition =
					updateFunctionDefinition(componentFunctionDefinition, newFunctionDefinition);

				return changedFunctionDefinition;
			}
		}

		return null;
	}

	/**
	 * Method to update the given function definition with the new function definition
	 * @param functionDefinition the given function definition
	 * @param newFunctionDefinition the new function definition
	 * @return the new function definition or null if not changed
	 */
	private FunctionDefinition updateFunctionDefinition(FunctionDefinition functionDefinition,
			FunctionDefinition newFunctionDefinition) {

		boolean changed = false;

		if (!functionDefinition.getName().equals(newFunctionDefinition.getName())) {
			try {
				functionDefinition.setName(newFunctionDefinition.getName());
				changed = true;
			}
			catch (InvalidNameException | DuplicateNameException e) {
				// don't update if an exception
			}

		}

		ParameterDefinition[] currentArgs = functionDefinition.getArguments();
		ParameterDefinition[] changedArgs = newFunctionDefinition.getArguments();

		// only update if there are differences and the func sigs have same length
		// if they don't have same length then something was possibly overridden in a child and
		// it needs to stay the same as it was except the name
		if (!currentArgs.equals(changedArgs) && (currentArgs.length == changedArgs.length)) {
			ParameterDefinition[] newArgs = new ParameterDefinition[currentArgs.length];
			for (int i = 0; i < currentArgs.length; i++) {
				if (currentArgs[i].getName().equals("this") ||
					currentArgs[i].equals(changedArgs[i])) {
					newArgs[i] = currentArgs[i];
				}
				else {
					newArgs[i] = changedArgs[i];
					changed = true;
				}
			}
			if (changed) {
				functionDefinition.setArguments(newArgs);
			}

		}

		if (!functionDefinition.getReturnType().equals(newFunctionDefinition.getReturnType())) {
			functionDefinition.setReturnType(newFunctionDefinition.getReturnType());
			changed = true;
		}
		if (changed) {
			return functionDefinition;
		}
		return null;

	}

	public List<Structure> getClassStructures() throws CancelledException {

		Category category = program.getDataTypeManager().getCategory(classDataTypesCategoryPath);
		if (category == null) {
			return null;
		}

		Category[] subCategories = category.getCategories();
		return getClassStructures(subCategories);
	}

	private List<Structure> getClassStructures(Category[] categories) throws CancelledException {

		List<Structure> classStructures = new ArrayList<Structure>();

		for (Category category : categories) {
			monitor.checkCanceled();
			DataType[] dataTypes = category.getDataTypes();
			for (DataType dataType : dataTypes) {
				monitor.checkCanceled();

				// if the data type name is the same as the folder name then
				// it is the main class structure so add it
				if (dataType.getName().equals(category.getName()) &&
					dataType instanceof Structure) {

					Structure classStructure = (Structure) dataType;
					classStructures.add(classStructure);
				}
			}

			Category[] subcategories = category.getCategories();

			if (subcategories.length > 0) {
				classStructures.addAll(getClassStructures(subcategories));
			}
		}
		return classStructures;
	}

	public List<Object> updateList(List<Object> mainList, List<Object> itemsToAdd)
			throws CancelledException {

		if (itemsToAdd == null) {
			return mainList;
		}
		if (itemsToAdd.isEmpty()) {
			return mainList;
		}

		for (Object item : itemsToAdd) {
			monitor.checkCanceled();
			if (!mainList.contains(item)) {
				mainList.add(item);
			}
		}
		return mainList;
	}

	public List<Structure> getStructuresOnList(List<Object> list) throws CancelledException {

		List<Structure> structures = new ArrayList<Structure>();
		for (Object item : list) {
			monitor.checkCanceled();
			if (item instanceof Structure) {
				structures.add((Structure) item);
			}
		}
		return structures;
	}

	public List<FunctionDefinition> getFunctionDefinitionsOnList(List<Object> list)
			throws CancelledException {

		List<FunctionDefinition> functionDefs = new ArrayList<FunctionDefinition>();
		for (Object item : list) {
			monitor.checkCanceled();
			if (item instanceof FunctionDefinition) {
				functionDefs.add((FunctionDefinition) item);
			}
		}
		return functionDefs;
	}

	public List<Function> getFunctionsOnList(List<Object> list) throws CancelledException {

		List<Function> functions = new ArrayList<Function>();
		for (Object item : list) {
			monitor.checkCanceled();
			if (item instanceof Function) {
				functions.add((Function) item);
			}
		}
		return functions;
	}

	/**
	 * Method to apply the given function definition to the corresponding function signatures that
	 * do not match and also update the vftable structure fields to have the correct name if
	 * different from the changed one.
	 * @param functionDefinition the new function definition to apply
	 * @return a list of items changed
	 * @throws CancelledException if cancelled
	 * @throws DuplicateNameException if any changes cause duplicate name exceptions
	 * @throws DataTypeDependencyException if any changes cause data type dependency issues
	 * @throws InvalidInputException if there are invalid inputs when performing changes
	 */
	public List<Object> applyNewFunctionDefinition(FunctionDefinition functionDefinition)
			throws CancelledException, DuplicateNameException, DataTypeDependencyException,
			InvalidInputException {

		List<Object> changedItems = new ArrayList<Object>();

		// get the corresponding existing pointer for this function definition from the dtManager
		Pointer pointer = getPointerDataType(functionDefinition);
		if (pointer == null) {
			throw new IllegalArgumentException(
				"Cannot find existing pointer data type for " + functionDefinition.getName());
		}

		// get the vftable structures that contain the pointer to the function definition
		Collection<DataType> dataTypesContaining = pointer.getParents();

		for (DataType dataTypeContaining : dataTypesContaining) {
			monitor.checkCanceled();

			if (!(dataTypeContaining instanceof Structure)) {
				continue;
			}

			Structure vftableStructure = (Structure) dataTypeContaining;

			if (!vftableStructure.getName().contains("_vftable")) {
				continue;
			}

			// get class namespace using the vftable structure
			Namespace vfunctionStructureNamespace = getClassNamespace(vftableStructure);

			//skip if not in the class data type folder so cannot get corresponding namespace
			if (vfunctionStructureNamespace == null) {
				continue;
			}
			
			Data vftableData =
				getVftableStructureFromListing(vfunctionStructureNamespace, vftableStructure);

			if (vftableData == null) {
				throw new IllegalArgumentException(
					"Cannot retrieve the associated vftable data from the listing for the given function definition " +
						functionDefinition.getName());
			}

			DataTypeComponent[] vftableComponents = vftableStructure.getComponents();

			int functionDefIndex = getVfunctionIndex(vftableStructure, pointer);

			if (functionDefIndex == -1) {
				throw new IllegalArgumentException(
					"Vftable structure does not contain pointer to the given function definition " +
						vftableStructure.getName());
			}

			DataTypeComponent vfunctionComponent = vftableComponents[functionDefIndex];

			// update container structure field names if the name of the function
			// definition has changed
			List<Object> changedStructs =
				updateComponentFieldName(vfunctionComponent, functionDefinition);

			// updateListingVfunctionSignature for indiv component and vftable addr
			if (changedStructs != null && !changedStructs.isEmpty()) {
				changedItems = updateList(changedItems, changedStructs);
			}

			int functionIndex = vfunctionComponent.getOrdinal();
			Object changedItem = updateListingVfunctionSignature(vftableData,
				vftableComponents[functionIndex], vftableData.getAddress());
			if (changedItem != null && !changedItems.contains(changedItem)) {
				changedItems.add(changedItem);
			}

		}

		return changedItems;

	}

	/**
	 * Method to get the index of the vfunction pointer in the given vftable structure
	 * @param vftableStructure the given vftable structure
	 * @param pointerToVfunction the given vfunction pointer
	 * @return the index of the given vfunction pointer or -1 if not in structure
	 * @throws CancelledException if cancelled
	 */
	private int getVfunctionIndex(Structure vftableStructure, Pointer pointerToVfunction)
			throws CancelledException {

		int vfunctionIndex = -1;

		int numComponents = vftableStructure.getNumComponents();
		for (int i = 0; i < numComponents; i++) {
			monitor.checkCanceled();

			DataTypeComponent component = vftableStructure.getComponent(i);
			if (component.getDataType().equals(pointerToVfunction)) {
				return i;
			}
		}
		return vfunctionIndex;
	}

	/**
	 * Method to get the data type in the same folder as the given data type that is the pointer to
	 * the given data type. This is getting an existing pointer not trying to create a new one.
	 * @param dataType the given data type
	 * @return the existing pointer data type to the given data type in the same class dt folder
	 */
	private Pointer getPointerDataType(DataType dataType) {

		Category category = dataTypeManager.getCategory(dataType.getCategoryPath());

		DataType pointer = new PointerDataType(dataType, dataTypeManager);

		DataType dt = category.getDataType(pointer.getName());

		return (dt instanceof Pointer) ? (Pointer) dt : null;
	}

	/**
	 * Method to get the vftable data applied to the listing for the associated vftable structure
	 * @param classNamespace the given class Namespace
	 * @param vftableStructure the given class vftableStructure
	 * @return the associated vftable address or null if none found
	 * @throws CancelledException if cancelled
	 */
	private Data getVftableStructureFromListing(Namespace classNamespace,
			Structure vftableStructure) throws CancelledException {

		SymbolIterator classSymbols = symbolTable.getSymbols(classNamespace);
		while (classSymbols.hasNext()) {
			monitor.checkCanceled();
			Symbol classSymbol = classSymbols.next();
			if (!classSymbol.getName().contains(VFTABLE_LABEL)) {
				continue;
			}
			Address vftableAddress = classSymbol.getAddress();

			Data vftableData = program.getListing().getDataAt(vftableAddress);
			if (vftableData == null) {
				continue;
			}

			DataType vftableBaseDataType = vftableData.getBaseDataType();
			if (!(vftableBaseDataType instanceof Structure)) {
				continue;
			}

			Structure vftableStructureAtAddress = (Structure) vftableBaseDataType;
			if (vftableStructureAtAddress.equals(vftableStructure)) {
				return vftableData;
			}
		}
		return null;
	}

	public List<FunctionDefinition> getClassFunctionDefinitions(Namespace classNamespace)
			throws CancelledException {

		// get the data type category associated with the given class namespace
		Category category = getDataTypeCategory(classNamespace);

		// return null if there isn't one
		if (category == null) {
			return null;
		}

		// get the function definitions in the given data type category and add them to the list
		List<FunctionDefinition> functionDefs = new ArrayList<FunctionDefinition>();

		DataType[] classDataTypes = category.getDataTypes();
		for (DataType classDataType : classDataTypes) {
			monitor.checkCanceled();

			if (!(classDataType instanceof FunctionDefinition)) {
				continue;
			}
			FunctionDefinition functionDef = (FunctionDefinition) classDataType;

			functionDefs.add(functionDef);

		}
		return functionDefs;
	}

	/**
	 * Get the associated data type category for the given class namespace
	 * @param classNamespace the given class namespace
	 * @return the associated data type category or null if it doesn't exist
	 * @throws CancelledException if cancelled
	 */
	public Category getDataTypeCategory(Namespace classNamespace) throws CancelledException {

		// Make a CategoryPath for the given namespace
		CategoryPath classPath =
			extendedFlatAPI.createDataTypeCategoryPath(classDataTypesCategoryPath, classNamespace);

		// check to see if it exists in the data type manager and return it (it will return null
		// if it is not in the dtman
		Category category = dataTypeManager.getCategory(classPath);
		return category;
	}

	/**
	 * Method to get the class Namespace corresponding to the given data type. NOTE: The data type
	 * must be in the DTM_CLASS_DATA_FOLDER_NAME folder in the data type manager.
	 * @param dataType the given data type
	 * @return the class Namespace corresponding to the given data type or null if the data type
	 * is not in the DTM_CLASS_DATA_FOLDER_NAME folder or if class doesn't exist
	 * @throws CancelledException if cancelled
	 */
	public Namespace getClassNamespace(DataType dataType) throws CancelledException {

		if (dataType == null) {
			throw new IllegalArgumentException("DataType argument is null");
		}

		CategoryPath categoryPath = dataType.getCategoryPath();

		String path = categoryPath.getPath();

		if (!path.startsWith(DTM_CLASS_DATA_FOLDER_PATH)) {
			return null;
		}

		// strip off the leading class folder path and replace /'s with ::'s to get
		// class namespace path
		path = path.substring(DTM_CLASS_DATA_FOLDER_PATH.length());
		// TODO: update with regex to exclude very unlikely \/ case
		path = path.replace("/", "::");

		List<Namespace> namespaceByPath =
			NamespaceUtils.getNamespaceByPath(program, null, path);

		// ignore namespaces contained within libraries
		for (Namespace namespace : namespaceByPath) {
			if (!namespace.isExternal()) {
				return namespace;
			}
		}

		Msg.debug(this, "Expected clas namespace not found: " + path);
		return null;
	}

	/**
	 * Method to find any function definitions in the given vfunction structure that have changed
	 * and update the function signature data types
	 * @throws DuplicateNameException if try to rename with a duplicate name
	 * @throws DataTypeDependencyException if data type dependency error
	 * @throws InvalidInputException  if invalid name input
	 * @throws CancelledException if cancelled
	 */
	private Object updateListingVfunctionSignature(Data listingVftable,
			DataTypeComponent structureComponent, Address vftableAddress)
			throws DuplicateNameException, DataTypeDependencyException, InvalidInputException,
			CancelledException {

		int numVfunctions = listingVftable.getNumComponents();

		int newComponentOrdinal = structureComponent.getOrdinal();
		if (newComponentOrdinal > numVfunctions - 1) {
			return null;
		}

		Data dataComponent = listingVftable.getComponent(newComponentOrdinal);

		Reference[] referencesFrom = dataComponent.getReferencesFrom();
		if (referencesFrom.length != 1) {
			return null;
		}
		Address functionAddress = referencesFrom[0].getToAddress();
		Function vfunction = api.getFunctionAt(functionAddress);
		if (vfunction == null) {
			return null;
		}

		if (vfunction.isThunk()) {
			vfunction = vfunction.getThunkedFunction(true);
		}

		FunctionSignature listingFunctionSignature = vfunction.getSignature(true);

		if (listingFunctionSignature.getName().contains("purecall")) {
			return null;
		}

		DataType componentDataType = structureComponent.getDataType();
		if (!(componentDataType instanceof Pointer)) {
			return null;
		}

		Pointer pointer = (Pointer) componentDataType;
		DataType pointedToDataType = pointer.getDataType();

		if (!(pointedToDataType instanceof FunctionDefinition)) {
			return null;
		}

		FunctionDefinition newFunctionDefinition = (FunctionDefinition) pointedToDataType;

		if (areEquivalentFunctionSignatures(newFunctionDefinition, listingFunctionSignature)) {
			return null;
		}

		boolean changed = updateFunctionSignature(vfunction, newFunctionDefinition);
		if (changed) {
			return vfunction;
		}

		return null;

	}

	/**
	 * Method to update the given function's signature with the given function definition
	 * @param function the given function
	 * @param newFunctionDefinition the new function definition
	 * @return true if the update worked, false otherwise
	 */
	private boolean updateFunctionSignature(Function function,
			FunctionDefinition newFunctionDefinition) {

		// need to make sure the name on the function is the same as the function definition
		// or the follow-on apply cmd will not apply the signature. It only does so for functions
		// matching the function definition name
		if (!newFunctionDefinition.getName().equals(function.getName())) {
			try {
				function.setName(newFunctionDefinition.getName(), SourceType.USER_DEFINED);
			}
			catch (DuplicateNameException e) {
				// shouldn't get here with above check but if so, still need to continue on to
				// below to update the function signature
			}
			catch (InvalidInputException e) {
				return false;
			}
		}

		// update function signature at vfunction address with the function signature in the structure
		ApplyFunctionSignatureCmd cmd = new ApplyFunctionSignatureCmd(function.getEntryPoint(),
			newFunctionDefinition, SourceType.USER_DEFINED);
		cmd.applyTo(program);
		return true;
	}

	/**
	 * Method to compare the given FunctionDefinition data type with the given FunctionSignature to
	 * see if they are equivalent (ie same name, same return type, same param types, same param names,
	 * same calling convention, same hasVarArgs flag ...
	 * @param definition the given FunctionDefinition data type
	 * @param signature the given FunctionSignature
	 * @return true if the two are equivalent, false other wise
	 */
	public boolean areEquivalentFunctionSignatures(FunctionDefinition definition,
			FunctionSignature signature) {

		if ((DataTypeUtilities.equalsIgnoreConflict(signature.getName(), definition.getName())) &&
			DataTypeUtilities.isSameOrEquivalentDataType(definition.getReturnType(),
				signature.getReturnType()) &&
			(definition.getGenericCallingConvention() == signature.getGenericCallingConvention()) &&
			(definition.hasVarArgs() == signature.hasVarArgs())) {
			ParameterDefinition[] sigargs = signature.getArguments();
			ParameterDefinition[] defArgs = definition.getArguments();
			if (sigargs.length == defArgs.length) {
				for (int i = 0; i < sigargs.length; i++) {
					if (!defArgs[i].isEquivalent(sigargs[i])) {
						return false;
					}
					if (!defArgs[i].getName().equals(sigargs[i].getName())) {
						return false;
					}
				}
				return true;
			}
		}
		return false;
	}

	private List<Object> updateComponentFieldName(DataTypeComponent structureComponent,
			FunctionDefinition newFunctionSignature) throws DuplicateNameException {

		List<Object> changedItems = new ArrayList<Object>();
		if (!structureComponent.getFieldName().equals(newFunctionSignature.getName())) {
			structureComponent.setFieldName(newFunctionSignature.getName());
			changedItems.add(structureComponent);
			changedItems.add(structureComponent.getParent());
			return changedItems;
		}
		return changedItems;
	}

	/**
	 * Method to retrieve the vftable symbols in the given namespace
	 * @param classNamespace the given namespace
	 * @return a list of vftable symbols in the given namespace
	 * @throws CancelledException if cancelled
	 */
	public List<Symbol> getClassVftableSymbols(Namespace classNamespace) throws CancelledException {

		List<Symbol> vftableSymbols = new ArrayList<Symbol>();

		SymbolIterator symbols = symbolTable.getSymbols(classNamespace);
		while (symbols.hasNext()) {

			monitor.checkCanceled();
			Symbol symbol = symbols.next();
			if (symbol.getName().equals("vftable") ||
				symbol.getName().substring(1).startsWith("vftable") ||
				symbol.getName().contains("vftable_for_")) {
				vftableSymbols.add(symbol);
			}

		}
		return vftableSymbols;
	}

	public Namespace getClassNamespace(Address address) {
		Symbol primarySymbol = symbolTable.getPrimarySymbol(address);

		if (primarySymbol == null) {
			Function functionContaining = api.getFunctionContaining(address);
			if (functionContaining != null) {
				primarySymbol =
					program.getSymbolTable().getPrimarySymbol(functionContaining.getEntryPoint());
			}
			else {
				Data dataContaining = api.getDataContaining(address);
				if (dataContaining != null) {
					primarySymbol =
						program.getSymbolTable().getPrimarySymbol(dataContaining.getMinAddress());
				}
			}
			if (primarySymbol == null) {
				return null;
			}
		}

		Namespace classNamespace = primarySymbol.getParentNamespace();
		if (classNamespace.isGlobal()) {
			return null;
		}

		return classNamespace;
	}

	private Category getDataTypeCategory(DataType dataType) {

		CategoryPath originalPath = dataType.getCategoryPath();
		Category category = dataTypeManager.getCategory(originalPath);

		return category;
	}

	/**
	 * Method to add/overwrite class vftable pointers to the given class structure
	 * @param classStructureDataType the class structure data type
	 * @param recoveredClass the given recovered class
	 * @param vfPointerDataTypes the map of addresses/vftables, a null should be passed to indicate
	 * no known vftables for the given class.
	 * @return the modified structure with the vftable pointers added or an unchanged structure if
	 * the vftable map is null or if the given class's offset to vftable map is empty
	 * @throws CancelledException if cancelled
	 * @throws IllegalArgumentException if there are issues modifying the structure
	 */
	protected Structure addClassVftables(Structure classStructureDataType,
			RecoveredClass recoveredClass, Map<Address, DataType> vfPointerDataTypes)
			throws CancelledException, IllegalArgumentException {

		Map<Integer, Address> classOffsetToVftableMap = recoveredClass.getClassOffsetToVftableMap();
		Set<Integer> classVftableOffsets = classOffsetToVftableMap.keySet();

		if (vfPointerDataTypes == null || classVftableOffsets.isEmpty()) {
			return classStructureDataType;
		}

		// If the map is not complete, the class structure will contain incomplete information so
		// put out a debug message to indicate this issue
		if (!isClassOffsetToVftableMapComplete(recoveredClass)) {
			Msg.debug(this,
				"class vftable offset map for " + recoveredClass.getName() + " is not complete");
		}

		// iterate over the set of offsets to vftables and either add to undefined area or overwrite
		// the parent class structures with the class vftable pointer then replace the rest of the
		// parent structure with its internal components
		for (Integer offset : classVftableOffsets) {
			monitor.checkCanceled();

			Address vftableAddress = classOffsetToVftableMap.get(offset);

			int vftableOffset = offset.intValue();

			DataType classVftablePointer = vfPointerDataTypes.get(vftableAddress);

			if (classVftablePointer == null) {
				continue;
			}

			// loop until the vftable pointer is added
			// if there is room and the component offset is not a structure, replace with vftablePtr
			// otherwise, within the range from top of containing component to the end of where the
			// vftable it to replace, clear component(s) or replace structure(s) with internal
			// components and loop until can replace with the vftable pointer
			while (true) {

				monitor.checkCanceled();

				// if enough empty bytes or can grow the structure - add class vftable pointer
				boolean addedToStructure =
					EditStructureUtils.addDataTypeToStructure(classStructureDataType, vftableOffset,
						classVftablePointer, CLASS_VTABLE_PTR_FIELD_EXT, monitor);
				if (addedToStructure) {
					break;
				}

				// if the component at the offset is the start of the component, and the component
				// isn't a structure and if the component is big enough to be replaced, or there
				// are enough undefined that can be replaced then replace
				DataTypeComponent componentAt =
					classStructureDataType.getComponentAt(vftableOffset);

				if (componentAt != null && !(componentAt.getDataType() instanceof Structure) &&
					(componentAt.getLength() >= classVftablePointer.getLength() ||
						EditStructureUtils.hasEnoughUndefinedsOfAnyLengthAtOffset(
							classStructureDataType, vftableOffset, classVftablePointer.getLength(),
							monitor))) {

					EditStructureUtils.clearLengthAtOffset(classStructureDataType, vftableOffset,
						classVftablePointer.getLength(), monitor);
					classStructureDataType.replaceAtOffset(vftableOffset, classVftablePointer,
						classVftablePointer.getLength(), CLASS_VTABLE_PTR_FIELD_EXT, "");
					break;
				}

				// otherwise if in middle of a containing dt then loop until all structs in
				// range are expanded and other items are cleared
				DataTypeComponent currentComponent =
					classStructureDataType.getComponentContaining(vftableOffset);

				int currentOffset = currentComponent.getOffset();
				int endOffset = vftableOffset + classVftablePointer.getLength();
				while (currentComponent != null && currentOffset < endOffset) {

					int nextOffset = currentComponent.getEndOffset() + 1;
					// if there is a structure at the offset, replace it with its pieces
					if (currentComponent.getDataType() instanceof Structure) {
						DataType currentDT = currentComponent.getDataType();
						Structure internalStruct = (Structure) currentDT;
						expandInternalStructure(classStructureDataType, internalStruct,
							currentOffset);
					}
					// if not a structure then clear it
					else {
						classStructureDataType.clearAtOffset(currentOffset);
					}
					currentOffset = nextOffset;
					currentComponent = classStructureDataType.getComponentAt(currentOffset);
				}
			}
		}
		return classStructureDataType;
	}

	/**
	 * Method to replace an internal structure into individual parts within the containing structure
	 * @param structure the containing structure
	 * @param internalStruct the internal structure
	 * @param offset the offset of the internal structure
	 */
	private void expandInternalStructure(Structure structure, Structure internalStruct,
			int offset) {

		DataTypeComponent[] components = internalStruct.getComponents();

		// if there is an empty structure at the offset, clear it which will replace
		// it with an undefined data type of size 1
		if (components.length == 0) {
			structure.clearAtOffset(offset);
			return;
		}
		// if non-empty, replace the structure with its components
		for (DataTypeComponent component : components) {

			int innerOffset = component.getOffset();

			// add indiv components of internal structure to the outer structure
			structure.replaceAtOffset(offset + innerOffset, component.getDataType(),
				component.getLength(), component.getFieldName(), "");
		}
		return;
	}

	/**
	 * Method to add a pointer to the class vbtable to the given class's class structure
	 * @param recoveredClass the given class
	 * @param classStructureDataType the given class's class structure data type
	 * @param overwrite if true, overwrite existing item with the vbtable pointer, if false, don't
	 * @return the updated class structure
	 * @throws CancelledException if cancelled
	 */
	protected Structure addVbtableToClassStructure(RecoveredClass recoveredClass,
			Structure classStructureDataType, boolean overwrite) throws CancelledException {

		Structure vbtableStructure = recoveredClass.getVbtableStructure();

		if (vbtableStructure != null) {

			int vbtableOffset = recoveredClass.getVbtableOffset();

			DataType vbaseStructPointer = dataTypeManager.getPointer(vbtableStructure);

			// if it fits at offset or is at the end and class structure can be grown,
			// copy the whole baseClass structure to the class Structure at the given offset
			boolean addedToStructure = EditStructureUtils.addDataTypeToStructure(
				classStructureDataType, vbtableOffset, vbaseStructPointer, VBTABLE_PTR, monitor);
			if (!addedToStructure && overwrite && classStructureDataType
					.getLength() >= (vbtableOffset + vbaseStructPointer.getLength())) {
				classStructureDataType.replaceAtOffset(vbtableOffset, vbaseStructPointer,
					vbaseStructPointer.getLength(), VBTABLE_PTR, "");
			}
		}
		return classStructureDataType;
	}

}
