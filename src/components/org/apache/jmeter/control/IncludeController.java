/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.control;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.SearchByClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IncludeController extends GenericController implements ReplaceableController {
    private static final Logger log = LoggerFactory.getLogger(IncludeController.class);

    private static final long serialVersionUID = 241L;

    private static final String INCLUDE_PATH = "IncludeController.includepath"; //$NON-NLS-1$

    private static  final String PREFIX =
        JMeterUtils.getPropDefault(
                "includecontroller.prefix", //$NON-NLS-1$
                ""); //$NON-NLS-1$

    private static final String WORKBENCH = "WorkBench";

    private HashTree subtree = null;
    private TestElement sub = null;

    /**
     * No-arg constructor
     *
     * @see java.lang.Object#Object()
     */
    public IncludeController() {
        super();
    }

    @Override
    public Object clone() {
        // TODO - fix so that this is only called once per test, instead of at every clone
        // Perhaps save previous filename, and only load if it has changed?
        this.resolveReplacementSubTree(null);
        IncludeController clone = (IncludeController) super.clone();
        clone.setIncludePath(this.getIncludePath());
        if (this.subtree != null) {
            if (this.subtree.size() == 1) {
                for (Object o : this.subtree.keySet()) {
                    this.sub = (TestElement) o;
                }
            }
            clone.subtree = (HashTree)this.subtree.clone();
            clone.sub = this.sub==null ? null : (TestElement) this.sub.clone();
        }
        return clone;
    }

    /**
     * In the event an user wants to include an external JMX test plan
     * the GUI would call this.
     * @param jmxfile The path to the JMX test plan to include
     */
    public void setIncludePath(String jmxfile) {
        this.setProperty(INCLUDE_PATH,jmxfile);
    }

    /**
     * return the JMX file path.
     * @return the JMX file path
     */
    public String getIncludePath() {
        return this.getPropertyAsString(INCLUDE_PATH);
    }

    /**
     * The way ReplaceableController works is clone is called first,
     * followed by replace(HashTree) and finally getReplacement().
     */
    @Override
    public HashTree getReplacementSubTree() {
        /*
        Hack
        JMeter doesn't allow to import jmx test plans included into IncludeController and referenced by ModuleController
        more than once (ie depth > 1). The problem is that reference to IC is relative to test plan, but while importing
        we do not recalculate possible internal includes in newly referenced jmx test plan. This hack fixes this issue.
         */

        @SuppressWarnings("deprecation")
        JMeterTreeModel treeModel = new JMeterTreeModel(new Object());
        JMeterTreeNode root = (JMeterTreeNode) treeModel.getRoot();
        try {
            treeModel.addSubTree(this.subtree, root);
        } catch (IllegalUserActionException e) {
            log.warn("unable to create subtree model from import:" + getIncludePath(), e);
        }
        SearchByClass<ModuleController> moduleController = new SearchByClass<>(ModuleController.class);
        this.subtree.traverse(moduleController);
        Collection<ModuleController> moduleControllersRes = moduleController.getSearchResults();
        for (ModuleController mc : moduleControllersRes) {
            if (mc.getSelectedNode() == null) {
                List<?> nodePath = mc.getNodePath();
                if (nodePath != null && nodePath.size() > 0) {
                    Object entry = nodePath.get(0);
                    if (entry != null && entry.toString().equals(WORKBENCH)) {
                        nodePath.remove(entry);
                    }
                }
            }
            mc.resolveReplacementSubTree(root);
        }

        return subtree;
    }

    public TestElement getReplacementElement() {
        return sub;
    }

    @Override
    public void resolveReplacementSubTree(JMeterTreeNode context) {
        this.subtree = this.loadIncludedElements();
    }

    /**
     * load the included elements using SaveService
     *
     * @return tree with loaded elements
     */
    protected HashTree loadIncludedElements() {
        // only try to load the JMX test plan if there is one
        final String includePath = getIncludePath();
        HashTree tree = null;
        if (includePath != null && includePath.length() > 0) {
            String fileName=PREFIX+includePath;
            try {
                File file = new File(fileName.trim());
                final String absolutePath = file.getAbsolutePath();
                log.info("loadIncludedElements -- try to load included module: {}", absolutePath);
                if(!file.exists() && !file.isAbsolute()){
                    log.info("loadIncludedElements -failed for: {}", absolutePath);
                    file = new File(FileServer.getFileServer().getBaseDir(), includePath);
                    if (log.isInfoEnabled()) {
                        log.info("loadIncludedElements -Attempting to read it from: {}", file.getAbsolutePath());
                    }
                    if(!file.canRead() || !file.isFile()){
                        log.error("Include Controller '{}' can't load '{}' - see log for details", this.getName(),
                                fileName);
                        throw new IOException("loadIncludedElements -failed for: " + absolutePath +
                                " and " + file.getAbsolutePath());
                    }
                }
                
                tree = SaveService.loadTree(file);
                // filter the tree for a TestFragment.
                tree = getProperBranch(tree);
                removeDisabledItems(tree);
                return tree;
            } catch (NoClassDefFoundError ex) // Allow for missing optional jars
            {
                String msg = "Including file \""+ fileName 
                            + "\" failed for Include Controller \""+ this.getName()
                            +"\", missing jar file";
                log.warn(msg, ex);
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
            } catch (FileNotFoundException ex) {
                String msg = "File \""+ fileName 
                        + "\" not found for Include Controller \""+ this.getName()+"\"";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            } catch (Exception ex) {
                String msg = "Including file \"" + fileName 
                            + "\" failed for Include Controller \"" + this.getName()
                            +"\", unexpected error";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            }
        }
        return tree;
    }

    /**
     * Extract from tree (included test plan) all Test Elements located in a Test Fragment
     * @param tree HashTree included Test Plan
     * @return HashTree Subset within Test Fragment or Empty HashTree
     */
    private HashTree getProperBranch(HashTree tree) {
        for (Object o : new LinkedList<>(tree.list())) {
            TestElement item = (TestElement) o;

            //if we found a TestPlan, then gather all TestFragments from current tree node
            if (item instanceof TestPlan)
            {
                return tree.getTree(item);
            }
        }
        log.warn("No Test Fragment was found in included Test Plan, returning empty HashTree");
        return new HashTree();
    }


    private void removeDisabledItems(HashTree tree) {
        for (Object o : new LinkedList<>(tree.list())) {
            TestElement item = (TestElement) o;
            if (!item.isEnabled()) {
                // TestFragmentController is used to hold IncludeController which references external jmx files
                // we must keep such elements
                if (!(item instanceof TestFragmentController)) {
                    tree.remove(item);
                }
            } else {
                removeDisabledItems(tree.getTree(item));// Recursive call
            }
        }
    }

}
