import React from 'react';
import {fireEvent, render, wait} from "@testing-library/react";
import SaveChangesModal from "./save-changes-modal";
import axiosMock from 'axios'
import {saveQueryResponse, putQueryResponse } from "../../../../assets/mock-data/query";
import userEvent from "@testing-library/user-event";
jest.mock('axios');


describe("<SaveChangesModal/>", () => {
    afterEach(() => {
        jest.clearAllMocks();
    });

    let queryField, queryDescription, saveButton;
    let currentQuery = saveQueryResponse;

    test("Verify Save Changes modal fields are rendered with previous saved query values", async () => {
        const { getByPlaceholderText,getByText } = render(<SaveChangesModal
            setSaveChangesModalVisibility={jest.fn()}
            greyFacets= { [{constraint: "lastname", facet: "Adams", displayName: ''},
                {constraint: "lastname", facet: "Coleman", displayName: ''}]}
            toggleApply={jest.fn()}
            toggleApplyClicked={jest.fn()}
            setSaveNewIconVisibility={jest.fn()}
            currentQuery={currentQuery}
            currentQueryName={''}
            setCurrentQueryName={jest.fn()}
            currentQueryDescription={''}
            setCurrentQueryDescription={jest.fn()}
        />)
        queryField = getByPlaceholderText("Enter query name");
        expect(queryField).toHaveAttribute('value', 'Order query');
        queryDescription = getByPlaceholderText("Enter query description");
        expect(queryDescription).toHaveAttribute('value', 'saved order query');
        expect(getByText('Apply before saving')).toBeVisible();
        expect(getByText('Save as is, keep unapplied facets')).toBeVisible();
        expect(getByText('Discard unapplied facets')).toBeVisible();
    });

    test("Verify save changes modal details can be edited and saved", async () => {
        axiosMock.put.mockImplementationOnce(jest.fn(() => Promise.resolve({status: 200, data: putQueryResponse})));

        const { getByPlaceholderText, getByText } = render(<SaveChangesModal
            setSaveChangesModalVisibility={jest.fn()}
            greyFacets={[]}
            toggleApply={jest.fn()}
            toggleApplyClicked={jest.fn()}
            setSaveNewIconVisibility={jest.fn()}
            currentQuery={currentQuery}
            currentQueryName={''}
            setCurrentQueryName={jest.fn()}
            currentQueryDescription={''}
            setCurrentQueryDescription={jest.fn()}
        />)
        queryField = getByPlaceholderText("Enter query name");
        fireEvent.change(queryField, { target: {value: ''} });
        fireEvent.change(queryField, { target: {value: 'Edit new query'} });
        expect(queryField).toHaveAttribute('value', 'Edit new query');
        queryDescription = getByPlaceholderText("Enter query description");
        expect(queryDescription).toHaveAttribute('value', 'saved order query');
        await wait(() => {
            userEvent.click(getByText('Save'));
        });
       let payload = {
                "savedQuery": {
                    "id": "",
                    "name": "Edit new query",
                    "description": "saved order query",
                    "query": {
                       "entityTypeIds": [],
                        "searchText": "",
                         "selectedFacets": {},
                    },
                    "propertiesToDisplay": [
                        "facet1",
                        "EntityTypeProperty1"
                    ]
                }
        }


        let url = "/api/entitySearch/savedQueries";
        expect(axiosMock.put).toHaveBeenCalledWith(url, payload);
        expect(axiosMock.put).toHaveBeenCalledTimes(1);
    });

});