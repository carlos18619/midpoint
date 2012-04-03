/*
 * Copyright (c) 2012 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 *
 * Portions Copyrighted 2012 [name of copyright owner]
 */

package com.evolveum.midpoint.web.page.admin.configuration;

import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.web.component.button.AjaxSubmitLinkButton;
import com.evolveum.midpoint.web.component.util.LoadableModel;
import com.evolveum.midpoint.web.component.xml.ace.AceEditor;
import com.evolveum.midpoint.xml.ns._public.common.api_types_2.ImportOptionsType;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.model.Model;

/**
 * @author lazyman
 */
public class PageImportXml extends PageAdminConfiguration {

    private LoadableModel<ImportOptionsType> model;
    
    public PageImportXml() {
        model = new LoadableModel<ImportOptionsType>(false) {

            @Override
            protected ImportOptionsType load() {
                return MiscSchemaUtil.getDefaultImportOptions();
            }
        };
        initLayout();
    }
    
    private void initLayout() {
        Form mainForm = new Form("mainForm");
        add(mainForm);

        AceEditor<String> editor = new AceEditor<String>("aceEditor", new Model<String>("aaa"));
        mainForm.add(editor);

        ImportOptionsPanel importOptions = new ImportOptionsPanel("importOptions", model);
        mainForm.add(importOptions);

        initButtons(mainForm);
    }

    private void initButtons(final Form mainForm) {
        AjaxSubmitLinkButton saveButton = new AjaxSubmitLinkButton("importButton",
                createStringResource("pageImportXml.button.import")) {

            @Override
            protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
                //todo implement

                model.reset();
            }

            @Override
            protected void onError(AjaxRequestTarget target, Form<?> form) {
                //todo implement
            }
        };
        mainForm.add(saveButton);
    }
}
