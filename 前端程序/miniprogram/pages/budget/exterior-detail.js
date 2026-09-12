'use strict';
const { protectedPage } = require('../../utils/access');
const { budgetPage } = require('../../utils/budget-view');
protectedPage(budgetPage('EXTERIOR'));
